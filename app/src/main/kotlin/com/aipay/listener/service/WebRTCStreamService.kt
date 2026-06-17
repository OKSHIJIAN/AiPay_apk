package com.aipay.listener.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import androidx.core.app.NotificationCompat
import com.aipay.listener.BuildConfig
import com.aipay.listener.R
import com.aipay.listener.data.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import org.webrtc.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * 截屏推流服务（DataChannel P2P 模式）
 *
 * 使用 ImageReader 定时截取屏幕 → 压缩为 JPEG → 通过 WebRTC DataChannel 发送到网页端。
 * 信令走 Supabase Realtime，截图数据走 P2P 直连，不消耗服务器带宽。
 */
class WebRTCStreamService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var serviceJob: Job? = null

    // WebRTC (DataChannel only, no video codec needed)
    private var eglBase: EglBase? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null

    // Screen capture via ImageReader
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var screenWidth = 720
    private var screenHeight = 1280
    private val captureIntervalMs = 1500L  // 1.5 秒截一帧

    // Signaling (Supabase Realtime)
    private var signalingWs: WebSocket? = null
    private var okHttpClient: OkHttpClient? = null
    private var apiKey: String = ""
    private var channelTopic: String = ""
    private var realtimeUrl: String = ""
    private var msgSeq = 0

    // State
    private var isStreaming = false
    private var dcReady = false
    private var screenshotSent = false  // 首帧已发送 → 停止截取
    private val captureRunnable = CaptureTask()

    companion object {
        const val TAG = "WebRTCStream"
        const val CHANNEL_ID = "aipay_stream"
        const val NOTIFICATION_ID = 1002
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val DATA_CHUNK_SIZE = 15000  // 每块 ≤15KB，跨平台安全上限
        const val JPEG_QUALITY = 60        // JPEG 压缩质量

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, WebRTCStreamService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WebRTCStreamService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        DebugLog.i(TAG, "服务已创建（DataChannel 截屏模式）")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 必须在 5 秒内调用 startForeground
        try {
            startForeground(
                NOTIFICATION_ID,
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle("AiPay 截屏中")
                    .setContentText("屏幕截图正在传输到网页端")
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build()
            )
        } catch (e: Exception) {
            DebugLog.e(TAG, "startForeground 失败: ${e.message}", e)
        }

        DebugLog.i(TAG, "onStartCommand 收到启动指令")

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        DebugLog.i(TAG, "resultCode=$resultCode (RESULT_OK=-1), hasData=${data != null}")

        if (resultCode == Activity.RESULT_OK && data != null) {
            serviceJob?.cancel()
            serviceJob = scope.launch(Dispatchers.IO) {
                try {
                    startStreaming(resultCode, data)
                } catch (e: Exception) {
                    DebugLog.e(TAG, "startStreaming 崩溃: ${e.message}", e)
                    stopSelf()
                }
            }
        } else {
            DebugLog.w(TAG, "缺少录屏权限，无法启动截屏")
            stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        DebugLog.i(TAG, "服务销毁")
        stopStreaming()
        serviceJob?.cancel()
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── 通知渠道 ──
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "截屏服务", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    // ═════════════════════════════════════════════
    //  核心：开始截屏推流
    // ═════════════════════════════════════════════
    private suspend fun startStreaming(resultCode: Int, data: Intent) = withContext(Dispatchers.Main) {
        DebugLog.i(TAG, "startStreaming 开始, resultCode=$resultCode")
        try {
            val settingsRepo = SettingsRepository(this@WebRTCStreamService)
            val settings = settingsRepo.settings.first()
            apiKey = settings.apiKey
            DebugLog.i(TAG, "settings 已读取: apiKey=${apiKey.take(8)}...")

            if (apiKey.isBlank()) {
                DebugLog.w(TAG, "API Key 为空，无法启动截屏")
                return@withContext
            }
            if (!apiKey.startsWith("aip_")) {
                DebugLog.w(TAG, "API Key 格式不正确")
                return@withContext
            }

            val supabaseHost = BuildConfig.SUPABASE_URL
                .replace("https://", "")
                .replace("http://", "")
            realtimeUrl = "wss://$supabaseHost/realtime/v1/websocket?apikey=${BuildConfig.SUPABASE_ANON_KEY}&vsn=1.0.0"
            channelTopic = "realtime:webrtc_signaling_${apiKey.removePrefix("aip_")}"

            DebugLog.i(TAG, "启动截屏: channel=$channelTopic, apiKey=${apiKey.take(8)}...")

            // 步骤 1：初始化 WebRTC（简化版，无需视频编解码）
            try {
                initWebRTC()
                DebugLog.i(TAG, "步骤1: WebRTC 初始化完成")
            } catch (e: Exception) {
                DebugLog.e(TAG, "步骤1 失败(WebRTC初始化): ${e.message}", e)
                stopStreaming()
                return@withContext
            }

            // 步骤 2：启动 ImageReader 屏幕捕获
            try {
                startScreenCapture(resultCode, data)
                DebugLog.i(TAG, "步骤2: ImageReader 屏幕捕获已启动")
            } catch (e: Exception) {
                DebugLog.e(TAG, "步骤2 失败(屏幕捕获): ${e.message}", e)
                stopStreaming()
                return@withContext
            }

            // 步骤 3：连接信令服务器
            try {
                connectSignaling()
                DebugLog.i(TAG, "步骤3: 信令连接已发起")
            } catch (e: Exception) {
                DebugLog.e(TAG, "步骤3 失败(信令连接): ${e.message}", e)
                stopStreaming()
                return@withContext
            }

            isStreaming = true

        } catch (e: Exception) {
            DebugLog.e(TAG, "启动截屏失败(外层): ${e.message}", e)
            stopStreaming()
        }
    }

    // ═════════════════════════════════════════════
    //  WebRTC 初始化（仅 DataChannel，无视频 Track）
    // ═════════════════════════════════════════════
    private fun initWebRTC() {
        val egl = EglBase.create()
        eglBase = egl

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(this)
                .createInitializationOptions()
        )

        // 无需 VideoEncoderFactory/VideoDecoderFactory
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()

        DebugLog.i(TAG, "PeerConnectionFactory 初始化完成（DataChannel-only 模式）")
    }

    // ═════════════════════════════════════════════
    //  屏幕捕获（ImageReader 方式，非视频流）
    // ═════════════════════════════════════════════
    private fun startScreenCapture(resultCode: Int, data: Intent) {
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        val metrics = DisplayMetrics()
        val displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(0)
        display?.getRealMetrics(metrics)

        val fullW = metrics.widthPixels
        val fullH = metrics.heightPixels
        val maxDim = 720  // 截图分辨率上限，节省带宽
        val scale = if (fullW > fullH) maxDim.toFloat() / fullW else maxDim.toFloat() / fullH
        screenWidth = if (scale < 1f) (fullW * scale).toInt() else fullW
        screenHeight = if (scale < 1f) (fullH * scale).toInt() else fullH

        DebugLog.i(TAG, "屏幕捕获: orig=${fullW}x${fullH}, cap=${screenWidth}x${screenHeight}")

        // ImageReader: RGBA_8888 格式，缓冲 2 帧
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)

        // VirtualDisplay 将屏幕内容投射到 ImageReader 的 Surface
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "AiPayScreenCapture",
            screenWidth, screenHeight, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        DebugLog.i(TAG, "VirtualDisplay 已创建: ${screenWidth}x${screenHeight}")
    }

    // ═════════════════════════════════════════════
    //  定时截图任务
    // ═════════════════════════════════════════════
    private inner class CaptureTask : Runnable {
        override fun run() {
            if (!isStreaming || !dcReady) {
                // 继续调度，等待 DataChannel 就绪
                handler.postDelayed(this, captureIntervalMs)
                return
            }

            // 首帧已发送 → 停止定时截图，仅保持连接
            if (screenshotSent) return

            try {
                val image = imageReader?.acquireLatestImage()
                if (image != null) {
                    val jpegBytes = imageToJpeg(image)
                    image.close()

                    if (jpegBytes != null && jpegBytes.isNotEmpty()) {
                        DebugLog.i(TAG, "截图 JPEG: ${jpegBytes.size / 1024}KB, ${screenWidth}x${screenHeight}")
                        sendChunked(jpegBytes)
                        // 首帧发送后停止定时截图
                        screenshotSent = true
                        handler.removeCallbacks(captureRunnable)
                        DebugLog.i(TAG, "首帧已发送，停止截图（静态显示模式）")
                        return
                    }
                }
            } catch (e: Exception) {
                DebugLog.e(TAG, "截图处理异常: ${e.message}")
            }

            handler.postDelayed(this, captureIntervalMs)
        }
    }

    // Image → JPEG ByteArray
    private fun imageToJpeg(image: Image): ByteArray? {
        val planes = image.planes
        val buffer = planes[0].buffer.duplicate()
        buffer.rewind()
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val bitmap = if (rowPadding == 0) {
            val bmp = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buffer)
            bmp
        } else {
            // 行对齐补齐 → 先创建宽位图再裁剪
            val paddedWidth = rowStride / pixelStride
            val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(buffer)
            val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
            padded.recycle()
            cropped
        }

        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
        bitmap.recycle()
        return baos.toByteArray()
    }

    // ═════════════════════════════════════════════
    //  分块发送（避免 DataChannel 单条消息超限）
    // ═════════════════════════════════════════════
    private fun sendChunked(data: ByteArray) {
        val dc = dataChannel ?: return
        if (dc.state() != DataChannel.State.OPEN) {
            DebugLog.w(TAG, "DataChannel 未就绪，丢弃帧")
            return
        }

        var offset = 0
        while (offset < data.size) {
            val chunkSize = minOf(DATA_CHUNK_SIZE, data.size - offset)
            val chunk = ByteBuffer.allocateDirect(chunkSize)
            chunk.put(data, offset, chunkSize)
            chunk.flip()
            dc.send(DataChannel.Buffer(chunk, true))  // true = binary
            offset += chunkSize
        }
        // 发送 EOF 标记（文本消息），浏览器收到后拼接显示
        val eofBuf = ByteBuffer.wrap("EOF".toByteArray(Charsets.UTF_8))
        dc.send(DataChannel.Buffer(eofBuf, false))  // false = text
    }

    // 发送 keepalive（无新帧时通知浏览器连接仍活跃，防止帧超时断连）
    private fun sendKeepalive() {
        val dc = dataChannel ?: return
        if (dc.state() != DataChannel.State.OPEN) return
        try {
            val buf = ByteBuffer.wrap("KEEPALIVE".toByteArray(Charsets.UTF_8))
            dc.send(DataChannel.Buffer(buf, false))
        } catch (_: Exception) { }
    }

    // 快速截取首帧（DC 打开后立即重试，避免等 1.5s 才截）
    private var quickCaptureRetries = 0
    private val maxQuickRetries = 10  // 最多快速重试 10 次（~2 秒）

    private fun tryQuickCapture() {
        if (!isStreaming || !dcReady || screenshotSent) return

        try {
            val image = imageReader?.acquireLatestImage()
            if (image != null) {
                val jpegBytes = imageToJpeg(image)
                image.close()
                if (jpegBytes != null && jpegBytes.isNotEmpty()) {
                    DebugLog.i(TAG, "快速截图 JPEG: ${jpegBytes.size / 1024}KB, ${screenWidth}x${screenHeight}")
                    sendChunked(jpegBytes)
                    screenshotSent = true
                    quickCaptureRetries = 0
                    DebugLog.i(TAG, "首帧已发送，停止截图（静态显示模式）")
                    return
                }
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "快速截图异常: ${e.message}")
        }

        if (quickCaptureRetries < maxQuickRetries) {
            quickCaptureRetries++
            handler.postDelayed({ tryQuickCapture() }, 200)  // 200ms 后重试
        } else {
            // 快速重试耗尽，回到正常 1.5s 调度
            quickCaptureRetries = 0
            DebugLog.w(TAG, "快速截图重试耗尽，切换为定时调度")
            handler.post(captureRunnable)
        }
    }

    // ═════════════════════════════════════════════
    //  信令连接（Supabase Realtime）
    // ═════════════════════════════════════════════
    private fun connectSignaling() {
        okHttpClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()

        if (realtimeUrl.isBlank()) {
            DebugLog.e(TAG, "Realtime URL 为空，无法连接")
            return
        }

        DebugLog.i(TAG, "连接 Supabase Realtime: $realtimeUrl, 频道: $channelTopic")

        val request = Request.Builder().url(realtimeUrl).build()
        signalingWs = okHttpClient?.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                DebugLog.i(TAG, "Realtime WebSocket 已连接，加入频道: $channelTopic")
                val joinMsg = JSONObject().apply {
                    put("topic", channelTopic)
                    put("event", "phx_join")
                    put("payload", JSONObject().apply {
                        put("config", JSONObject().apply {
                            put("broadcast", JSONObject().apply {
                                put("self", false)
                            })
                        })
                    })
                    put("ref", "1")
                }
                webSocket.send(joinMsg.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val msg = JSONObject(text)
                    val event = msg.optString("event")
                    val topic = msg.optString("topic")
                    val ref = msg.optString("ref")

                    when {
                        // Phoenix heartbeat
                        event == "heartbeat" && topic == "phoenix" -> {
                            val reply = JSONObject().apply {
                                put("topic", "phoenix")
                                put("event", "heartbeat")
                                put("payload", JSONObject())
                                put("ref", ref)
                            }
                            webSocket.send(reply.toString())
                        }
                        // phx_join 回复
                        event == "phx_reply" && topic == channelTopic -> {
                            val status = msg.optJSONObject("payload")?.optString("status")
                            if (status == "ok") {
                                DebugLog.i(TAG, "频道加入成功，广播注册为 phone")
                                broadcastSignaling(JSONObject().apply {
                                    put("type", "register")
                                    put("apiKey", apiKey)
                                    put("role", "phone")
                                })
                            } else {
                                DebugLog.e(TAG, "频道加入失败: $status")
                            }
                        }
                        // 来自浏览器的 broadcast
                        event == "broadcast" && topic == channelTopic -> {
                            val payload = msg.optJSONObject("payload")
                            val payloadEvent = payload?.optString("event")
                            if (payloadEvent == "signaling") {
                                val signalingPayload = payload.optJSONObject("payload")
                                if (signalingPayload != null) {
                                    handleSignalingMessage(signalingPayload)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    DebugLog.e(TAG, "Realtime 消息解析失败: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                DebugLog.e(TAG, "Realtime 连接失败: ${t.message}")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                DebugLog.i(TAG, "Realtime 已断开: code=$code reason=$reason")
                scheduleReconnect()
            }
        })
    }

    // ── 处理信令消息 ──
    private fun handleSignalingMessage(msg: JSONObject) {
        try {
            val type = msg.optString("type")

            when (type) {
                "register" -> {
                    val role = msg.optString("role")
                    if (role == "browser") {
                        // 防重入：PC 非死亡状态时忽略重复的浏览器注册（NEW/CONNECTING/CONNECTED 都视为活跃）
                        val pcState = peerConnection?.connectionState()
                        if (pcState != null &&
                            pcState != PeerConnection.PeerConnectionState.CLOSED &&
                            pcState != PeerConnection.PeerConnectionState.FAILED) {
                            DebugLog.i(TAG, "忽略重复的浏览器注册（PC 已存在，状态: $pcState）")
                            return
                        }
                        DebugLog.i(TAG, "浏览器已注册，发起 Offer（DataChannel 模式）")
                        handler.post {
                            closePeerConnection()
                            createPeerConnectionAndOffer()
                        }
                    }
                }

                "sdp" -> {
                    val from = msg.optString("from")
                    val sdpObj = msg.optJSONObject("sdp") ?: return
                    val sdpType = sdpObj.optString("type")
                    val sdp = sdpObj.optString("sdp")
                    DebugLog.i(TAG, "收到 SDP: from=$from type=$sdpType")

                    if (sdpType == "answer") {
                        val sessionDescription = SessionDescription(
                            SessionDescription.Type.ANSWER, sdp
                        )
                        handler.post {
                            peerConnection?.setRemoteDescription(
                                object : SdpObserver {
                                    override fun onCreateSuccess(sdp: SessionDescription?) {}
                                    override fun onSetSuccess() {
                                        DebugLog.i(TAG, "Remote SDP(Answer) 设置成功")
                                    }
                                    override fun onCreateFailure(error: String?) {
                                        DebugLog.e(TAG, "Remote SDP 创建失败: $error")
                                    }
                                    override fun onSetFailure(error: String?) {
                                        DebugLog.e(TAG, "Remote SDP 设置失败: $error")
                                    }
                                },
                                sessionDescription
                            )
                        }
                    }
                }

                "ice" -> {
                    val candidateObj = msg.optJSONObject("candidate") ?: return
                    val sdpMid = candidateObj.optString("sdpMid")
                    val sdpMLineIndex = candidateObj.optInt("sdpMLineIndex")
                    val candidateStr = candidateObj.optString("candidate")

                    val candidate = IceCandidate(sdpMid, sdpMLineIndex, candidateStr)
                    handler.post {
                        peerConnection?.addIceCandidate(candidate)
                    }
                }
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "信令消息处理失败: ${e.message}")
        }
    }

    // ── 广播信令到 Supabase Realtime ──
    private fun broadcastSignaling(payload: JSONObject) {
        msgSeq++
        val msg = JSONObject().apply {
            put("topic", channelTopic)
            put("event", "broadcast")
            put("payload", JSONObject().apply {
                put("type", "broadcast")
                put("event", "signaling")
                put("payload", payload)
            })
            put("ref", msgSeq.toString())
        }
        signalingWs?.send(msg.toString())
    }

    // ═════════════════════════════════════════════
    //  创建 PeerConnection + DataChannel + Offer
    // ═════════════════════════════════════════════
    private fun createPeerConnectionAndOffer() {
        if (peerConnection != null) {
            DebugLog.w(TAG, "PeerConnection 已存在，关闭后重建")
            closePeerConnection()
        }

        val rtcConfig = PeerConnection.RTCConfiguration(
            listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
            )
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate != null) {
                    broadcastSignaling(JSONObject().apply {
                        put("type", "ice")
                        put("from", "phone")
                        put("candidate", JSONObject().apply {
                            put("sdpMid", candidate.sdpMid)
                            put("sdpMLineIndex", candidate.sdpMLineIndex)
                            put("candidate", candidate.sdp)
                        })
                    })
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                DebugLog.i(TAG, "ICE 状态: $state")
            }
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
                DebugLog.i(TAG, "连接状态: $state")
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                DebugLog.i(TAG, "ICE 收集状态: $state")
            }
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}

            override fun onDataChannel(channel: DataChannel?) {
                DebugLog.i(TAG, "收到远程 DataChannel: ${channel?.label()}")
                // 浏览器可能会创建自己的 DataChannel，暂不使用
            }

            override fun onRenegotiationNeeded() {
                DebugLog.i(TAG, "需要重新协商")
            }
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
            override fun onStandardizedIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onTrack(transceiver: RtpTransceiver?) {}
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, observer)

        // ── 创建 DataChannel（手机 → 浏览器发送截图）──
        val dcInit = DataChannel.Init().apply {
            ordered = false      // 允许乱序，提高吞吐
            maxRetransmits = 0   // 不重传，实时性优先（丢帧比延迟好）
        }
        dataChannel = peerConnection?.createDataChannel("screenshot", dcInit)
        dataChannel?.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                val state = dataChannel?.state()
                DebugLog.i(TAG, "DataChannel 状态: $state")
                if (state == DataChannel.State.OPEN) {
                    dcReady = true
                    DebugLog.i(TAG, "DataChannel 已就绪，立即尝试截图")
                    // 立即截取首帧，不等待定时调度
                    handler.removeCallbacks(captureRunnable)
                    tryQuickCapture()
                } else {
                    dcReady = false
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer?) {}
        })

        DebugLog.i(TAG, "DataChannel 'screenshot' 已创建")

        // 创建 SDP Offer
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp == null) return
                handler.post {
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(sdp: SessionDescription?) {}
                        override fun onSetSuccess() {
                            DebugLog.i(TAG, "Local SDP(Offer) 设置成功")
                            broadcastSignaling(JSONObject().apply {
                                put("type", "sdp")
                                put("from", "phone")
                                put("sdp", JSONObject().apply {
                                    put("type", "offer")
                                    put("sdp", peerConnection?.localDescription?.description ?: "")
                                })
                            })
                            DebugLog.i(TAG, "SDP Offer 已广播（DataChannel 模式）")
                        }
                        override fun onCreateFailure(error: String?) {
                            DebugLog.e(TAG, "Local SDP 创建失败: $error")
                        }
                        override fun onSetFailure(error: String?) {
                            DebugLog.e(TAG, "Local SDP 设置失败: $error")
                        }
                    }, sdp)
                }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                DebugLog.e(TAG, "SDP Offer 创建失败: $error")
            }
            override fun onSetFailure(error: String?) {
                DebugLog.e(TAG, "SDP Offer 设置失败: $error")
            }
        }, MediaConstraints())
    }

    // ── 关闭 PeerConnection ──
    private fun closePeerConnection() {
        dcReady = false
        screenshotSent = false  // 重连后允许重新截图
        quickCaptureRetries = 0
        handler.removeCallbacks(captureRunnable)

        dataChannel?.close()
        dataChannel = null
        peerConnection?.close()
        peerConnection = null
        DebugLog.i(TAG, "PeerConnection 已关闭")
    }

    // ── 重连调度 ──
    private fun scheduleReconnect() {
        dcReady = false
        handler.removeCallbacks(captureRunnable)
        handler.postDelayed({
            if (!isStreaming) return@postDelayed
            DebugLog.i(TAG, "尝试重连信令...")
            connectSignaling()
        }, 3000)
    }

    // ═════════════════════════════════════════════
    //  停止所有资源
    // ═════════════════════════════════════════════
    private fun stopStreaming() {
        isStreaming = false
        dcReady = false
        handler.removeCallbacks(captureRunnable)

        handler.post {
            try {
                signalingWs?.close(1000, "service stopped")
                signalingWs = null
                okHttpClient?.dispatcher?.executorService?.shutdown()
                okHttpClient?.connectionPool?.evictAll()
                okHttpClient = null

                closePeerConnection()

                virtualDisplay?.release()
                virtualDisplay = null

                imageReader?.close()
                imageReader = null

                mediaProjection?.stop()
                mediaProjection = null

                peerConnectionFactory?.dispose()
                peerConnectionFactory = null

                eglBase?.release()
                eglBase = null

                DebugLog.i(TAG, "所有资源已释放")
            } catch (e: Exception) {
                DebugLog.e(TAG, "清理资源异常: ${e.message}", e)
            }
        }
    }
}
