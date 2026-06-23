package com.aipay.listener.service

import android.content.Context
import android.util.Log
import com.aipay.listener.BuildConfig
import com.aipay.listener.data.AppDatabase
import com.aipay.listener.data.AppSettings
import com.aipay.listener.data.LogStatus
import com.aipay.listener.data.PaymentLog
import com.aipay.listener.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 基于 Supabase Realtime Broadcast 的双向通信客户端。
 *
 * 数据流：
 *   网页（phone-monitor-page）进入监听页 → broadcast "request_status"
 *   → 手机收到 → 查询本地 Room DB → broadcast "phone_data"
 *   → 网页收到 → 渲染 UI
 */
class RealtimeClient(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val refCounter = AtomicInteger(0)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // 长连接不超时
        .build()

    private var webSocket: WebSocket? = null
    private var channelTopic: String = ""
    private var merchantId: String = ""
    private var connected = false
    private var serviceStartTime: Long = 0

    /** 启动 Realtime 连接（应在 KeepAliveService.onCreate 中调用） */
    fun start() {
        serviceStartTime = System.currentTimeMillis()
        scope.launch { connectLoop() }
    }

    /** 停止 Realtime 连接 */
    fun stop() {
        scope.cancel()
        webSocket?.close(1000, "service stopped")
        webSocket = null
        connected = false
        if (Companion.instance == this) Companion.instance = null
    }

    /**
     * 主动推送最新支付数据给网页端。
     * 由 PayNotificationListener 在成功上报后调用，确保网页立即收到更新。
     */
    fun broadcastCurrentStatus() {
        if (!connected || webSocket == null) {
            Log.d("AiPay", "[Realtime] 未连接，跳过 broadcastCurrentStatus")
            return
        }
        scope.launch {
            respondWithStatus()
            Log.d("AiPay", "[Realtime] 主动推送 phone_data 完成")
        }
    }

    /** 检查当前是否已连接 */
    fun isConnected(): Boolean = connected

    // ── 连接循环（断线自动重连）──
    private suspend fun connectLoop() {
        while (true) {
            try {
                val settings = settingsRepository.settings.first()
                if (settings.apiKey.isBlank()) {
                    Log.d("AiPay", "[Realtime] API Key 为空，跳过连接")
                    delay(10_000)
                    continue
                }
                if (!settings.monitoringEnabled) {
                    Log.d("AiPay", "[Realtime] 监听未启用，跳过连接")
                    delay(10_000)
                    continue
                }

                // 1. 解析 merchantId
                merchantId = resolveMerchantId(settings.apiKey)
                if (merchantId.isBlank()) {
                    Log.w("AiPay", "[Realtime] 无法解析 merchantId，10s 后重试")
                    delay(10_000)
                    continue
                }

                channelTopic = "realtime:phone:$merchantId"
                Log.d("AiPay", "[Realtime] 连接 channel=$channelTopic")

                // 2. 建立 WebSocket
                val wsUrl = "wss://qlsmtkqdvbionwpmhoyu.supabase.co/realtime/v1/websocket" +
                    "?apikey=${BuildConfig.SUPABASE_ANON_KEY}&vsn=1.0.0"
                val request = Request.Builder().url(wsUrl).build()

                val latch = java.util.concurrent.CountDownLatch(1)
                webSocket = client.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(ws: WebSocket, response: Response) {
                        Log.d("AiPay", "[Realtime] WebSocket 已连接")
                        connected = true
                        joinChannel()
                    }

                    override fun onMessage(ws: WebSocket, text: String) {
                        handleMessage(text)
                    }

                    override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                        Log.d("AiPay", "[Realtime] WebSocket closing: $code $reason")
                        connected = false
                        ws.close(1000, null)
                        latch.countDown()
                    }

                    override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                        Log.e("AiPay", "[Realtime] WebSocket 失败: ${t.message}")
                        connected = false
                        latch.countDown()
                    }
                })

                // 等待连接断开（阻塞当前协程）
                latch.await()
                Log.d("AiPay", "[Realtime] 连接断开，5s 后重连...")
                delay(5_000)

            } catch (e: Exception) {
                Log.e("AiPay", "[Realtime] 异常: ${e.message}，10s 后重试")
                delay(10_000)
            }
        }
    }

    // ── 加入 Broadcast Channel ──
    private fun joinChannel() {
        val ref = nextRef()
        val msg = buildJsonObject {
            put("topic", channelTopic)
            put("event", "phx_join")
            putJsonObject("payload") {
                putJsonObject("config") {
                    putJsonObject("broadcast") {
                        put("self", true)
                    }
                }
            }
            put("ref", ref)
        }
        send(json.encodeToString(msg))
        Log.d("AiPay", "[Realtime] 发送 phx_join ref=$ref")
    }

    // ── 消息处理 ──
    private fun handleMessage(text: String) {
        try {
            val msg = json.decodeFromString<kotlinx.serialization.json.JsonObject>(text)
            val event = msg["event"]?.toString()?.trim('"') ?: return

            when (event) {
                "heartbeat" -> {
                    // 回复心跳保持连接
                    val ref = msg["ref"]?.toString()?.trim('"')
                    val resp = buildJsonObject {
                        put("event", "heartbeat")
                        put("topic", "phoenix")
                        if (ref != null) put("ref", ref)
                        putJsonObject("payload") {}
                    }
                    send(json.encodeToString(resp))
                }

                "phx_reply" -> {
                    val status = msg["payload"]?.jsonObject?.get("status")?.toString()?.trim('"')
                    Log.d("AiPay", "[Realtime] phx_reply status=$status")
                }

                "broadcast" -> {
                    val payload = msg["payload"]?.jsonObject ?: return
                    val broadcastEvent = payload["event"]?.toString()?.trim('"') ?: return
                    Log.d("AiPay", "[Realtime] 收到 broadcast: $broadcastEvent")

                    when (broadcastEvent) {
                        "request_status" -> {
                            scope.launch { respondWithStatus() }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AiPay", "[Realtime] 消息解析失败: ${e.message}")
        }
    }

    // ── 查询本地数据并响应 ──
    private suspend fun respondWithStatus() {
        try {
            val dao = AppDatabase.get(context).logDao()
            val todayStart = LocalDate.now()
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()

            val recent = dao.getRecentSince(todayStart, 20)
            val captured = dao.getCapturedCountOnce(todayStart)
            val matched = dao.getCountByStatusOnce(todayStart, LogStatus.SUCCESS)
            val failed = dao.getCountByStatusOnce(todayStart, LogStatus.FAILED)

            val payments = recent.map { log ->
                buildJsonObject {
                    put("id", log.id.toString())
                    put("amount", log.amount)
                    put("channel", log.channel)
                    put("receivedAt", log.timestamp)
                    put("orderId", "")
                    put("matched", log.status == LogStatus.SUCCESS)
                    put("status", log.status)
                    put("title", log.title)
                    put("text", log.text.take(100))
                }
            }

            // 服务运行时长 = 当前时间 - serviceStartTime
            val uptime = if (serviceStartTime > 0) (System.currentTimeMillis() - serviceStartTime) / 1000 else 0

            val payload = buildJsonObject {
                put("online", true)
                put("uptime", uptime)
                putJsonObject("todayStats") {
                    put("captured", captured)
                    put("matched", matched)
                    put("unmatched", failed)  // 与手机 APK 首页一致：失败 = FAILED 状态
                }
                put("recentPayments", json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(
                        kotlinx.serialization.json.JsonObject.serializer()
                    ),
                    payments
                ).let { json.parseToJsonElement(it) })
                put("merchantId", merchantId)
            }

            sendBroadcast("phone_data", payload)
            Log.d("AiPay", "[Realtime] 响应 phone_data: captured=$captured matched=$matched logs=${recent.size}")
        } catch (e: Exception) {
            Log.e("AiPay", "[Realtime] 响应状态失败: ${e.message}")
        }
    }

    // ── 发送 Broadcast ──
    private fun sendBroadcast(event: String, payload: JsonObject) {
        val msg = buildJsonObject {
            put("topic", channelTopic)
            put("event", "broadcast")
            putJsonObject("payload") {
                put("type", "broadcast")
                put("event", event)
                put("payload", payload)
            }
            put("ref", nextRef())
        }
        send(json.encodeToString(msg))
    }

    private fun send(text: String) {
        webSocket?.send(text)
    }

    private fun nextRef() = (refCounter.incrementAndGet() % 100000).toString()

    // ── 从 API Key 解析 merchantId ──
    private suspend fun resolveMerchantId(apiKey: String): String = withContext(Dispatchers.IO) {
        runCatching {
            val resolveClient = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
            val url = "https://qlsmtkqdvbionwpmhoyu.supabase.co/rest/v1/kv_store_41dc007f" +
                "?select=value&key=eq.${java.net.URLEncoder.encode("apikey:$apiKey", "UTF-8")}"
            val req = Request.Builder()
                .url(url)
                .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .header("Authorization", "Bearer ${BuildConfig.SUPABASE_ANON_KEY}")
                .get()
                .build()
            resolveClient.newCall(req).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                // 返回的是 [{value: "merchantId"}]
                val entries = json.decodeFromString<List<kotlinx.serialization.json.JsonObject>>(body)
                entries.firstOrNull()?.get("value")?.toString()?.trim('"') ?: ""
            }
        }.getOrDefault("")
    }

    companion object {
        @Volatile
        var instance: RealtimeClient? = null
    }
}
