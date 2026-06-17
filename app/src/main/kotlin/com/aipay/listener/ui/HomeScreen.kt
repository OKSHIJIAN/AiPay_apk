package com.aipay.listener.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.request.ImageRequest
import com.aipay.listener.data.AppSettings
import com.aipay.listener.data.PaymentLog
import com.aipay.listener.ui.theme.AiBlack
import com.aipay.listener.ui.theme.AiMuted
import com.aipay.listener.ui.theme.AiYellow

@Composable
fun HomeScreen(
    settings: AppSettings,
    hasNotificationAccess: Boolean,
    isListenerConnected: Boolean = false,
    lastConnectedAt: Long = 0,
    serverOnline: Boolean,
    recentLogs: List<PaymentLog>,
    captured: Int,
    success: Int,
    failed: Int,
    onToggleMonitoring: (Boolean) -> Unit,
    onOpenNotificationSettings: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { Text("AiPay转发器", style = MaterialTheme.typography.headlineLarge) }
        item {
            RunningStatusCard(
                running = settings.monitoringEnabled,
                hasNotificationAccess = hasNotificationAccess,
                isListenerConnected = isListenerConnected,
                lastConnectedAt = lastConnectedAt,
                serverOnline = serverOnline,
                onOpenNotificationSettings = onOpenNotificationSettings
            )
        }
        item {
            BrutalCard(modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("转发总开关", style = MaterialTheme.typography.titleMedium)
                        Text("开启后启动前台保活服务")
                    }
                    Switch(
                        checked = settings.monitoringEnabled,
                        onCheckedChange = onToggleMonitoring,
                        colors = SwitchDefaults.colors(checkedThumbColor = AiYellow)
                    )
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatBox("今日捕获", captured.toString(), Modifier.weight(1f))
                StatBox("成功上报", success.toString(), Modifier.weight(1f))
                StatBox("失败", failed.toString(), Modifier.weight(1f))
            }
        }
        item { Text("最近 20 条", style = MaterialTheme.typography.titleLarge) }
        if (recentLogs.isEmpty()) {
            item { BrutalCard(modifier = Modifier.fillMaxWidth()) { Text("暂无上报历史") } }
        } else {
            items(recentLogs, key = { it.id }) { log -> LogRow(log) }
        }
    }
}

@Composable
private fun RunningStatusCard(
    running: Boolean,
    hasNotificationAccess: Boolean,
    isListenerConnected: Boolean = false,
    lastConnectedAt: Long = 0,
    serverOnline: Boolean,
    onOpenNotificationSettings: () -> Unit
) {
    // 无通知权限时卡片置灰，不显示「运行中」
    val isEffectivelyRunning = running && hasNotificationAccess
    val background = if (isEffectivelyRunning) AiYellow else AiMuted
    val srvDotColor = if (serverOnline) Color(0xFF1F9D55) else Color(0xFFEF4444)

    // 正计时：每秒更新一次（用 rememberUpdatedState 读取 lastConnectedAt 最新值）
    val currentLastConnectedAt by rememberUpdatedState(lastConnectedAt)
    var elapsedSeconds by remember { mutableStateOf(0L) }
    LaunchedEffect(isEffectivelyRunning) {
        if (isEffectivelyRunning) {
            while (true) {
                elapsedSeconds = if (currentLastConnectedAt > 0) {
                    (System.currentTimeMillis() - currentLastConnectedAt) / 1000
                } else 0
                delay(1000)
            }
        } else {
            elapsedSeconds = 0
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 132.dp)
            .background(background, RectangleShape)
            .border(1.5.dp, AiBlack, RectangleShape)
    ) {
        // GIF 斜线动效：仅有效运行时显示
        if (isEffectivelyRunning) {
            val context = LocalContext.current
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(com.aipay.listener.R.raw.stripe_animation)
                    .decoderFactory(GifDecoder.Factory())
                    .build(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .align(Alignment.BottomCenter),
                contentScale = ContentScale.Crop
            )
        }

        // 服务器状态指示（右上角）
        ServerStatusDot(
            dotColor = srvDotColor,
            label = if (serverOnline) "服务器在线" else "服务器离线",
            pulsing = serverOnline,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 10.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                when {
                    !hasNotificationAccess -> "需要授权"
                    isEffectivelyRunning -> "运行中"
                    else -> "已停止"
                },
                color = AiBlack,
                style = MaterialTheme.typography.headlineLarge
            )
            Text(
                when {
                    !hasNotificationAccess ->
                        "请授予「通知使用权」才能捕获支付消息"
                    isEffectivelyRunning && isListenerConnected ->
                        "${formatDuration(elapsedSeconds)}  ·  正在捕获收款消息"
                    isEffectivelyRunning && !isListenerConnected ->
                        "${formatDuration(elapsedSeconds)}  ·  ⚠ 监听器未连接"
                    else -> "总开关已关闭"
                },
                color = AiBlack
            )
            if (!hasNotificationAccess) {
                BrutalButton(
                    "去授权",
                    onOpenNotificationSettings,
                    Modifier.fillMaxWidth().padding(top = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun ServerStatusDot(
    dotColor: Color,
    label: String,
    pulsing: Boolean,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition()
    val dotAlpha by if (pulsing) {
        infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(
                animation = tween(800),
                repeatMode = RepeatMode.Reverse
            )
        )
    } else {
        infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1))
        )
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(dotColor.copy(alpha = dotAlpha))
                .border(1.dp, AiBlack, CircleShape)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = AiBlack
        )
    }
}

private fun formatDuration(totalSeconds: Long): String {
    if (totalSeconds <= 0) return "00:00"
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) {
        "${h}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    } else {
        "${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    }
}

