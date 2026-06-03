package com.aipay.listener.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    recentLogs: List<PaymentLog>,
    captured: Int,
    success: Int,
    failed: Int,
    onToggleMonitoring: (Boolean) -> Unit,
    onOpenNotificationSettings: () -> Unit
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
    onOpenNotificationSettings: () -> Unit
) {
    val background = if (running) AiYellow else AiMuted

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 132.dp)
            .background(background, RectangleShape)
            .border(1.5.dp, AiBlack, RectangleShape)
    ) {
        // GIF 斜线动效：仅运行时显示，固定在卡片底部区域
        if (running) {
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                if (running) "运行中" else "已停止",
                color = AiBlack,
                style = MaterialTheme.typography.headlineLarge
            )
            Text(
                when {
                    running && hasNotificationAccess -> "正在捕获收款消息"
                    !hasNotificationAccess -> "系统通知监听：未授权"
                    else -> "系统通知监听：已授权"
                },
                color = AiBlack
            )
            if (!hasNotificationAccess && !running) {
                BrutalButton(
                    "去授权",
                    onOpenNotificationSettings,
                    Modifier.fillMaxWidth().padding(top = 6.dp)
                )
            }
        }
    }
}
