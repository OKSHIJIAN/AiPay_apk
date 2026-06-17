package com.aipay.listener.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aipay.listener.service.DebugLog
import com.aipay.listener.service.LogEntry
import com.aipay.listener.ui.theme.AiBlack
import com.aipay.listener.ui.theme.AiGreen
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun DebugScreen() {
    val context = LocalContext.current
    val logs by DebugLog.entries.collectAsState()
    val listState = rememberLazyListState()
    var filterLevel by remember { mutableStateOf<String?>(null) }

    val filtered = remember(logs, filterLevel) {
        if (filterLevel == null) logs else logs.filter { it.level == filterLevel }
    }

    // 自动滚到底部
    LaunchedEffect(filtered.size) {
        if (filtered.isNotEmpty()) {
            listState.animateScrollToItem(filtered.size - 1)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 标题栏
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("调试日志", style = MaterialTheme.typography.headlineLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 一键复制
                BrutalButton("复制全部", onClick = {
                    val text = DebugLog.allText()
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("debug_logs", text))
                    Toast.makeText(context, "已复制 ${filtered.size} 条日志", Toast.LENGTH_SHORT).show()
                }, filled = true)

                // 清空
                BrutalButton("清空", onClick = {
                    DebugLog.clear()
                }, filled = false)
            }
        }

        // 筛选标签
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip("全部", filterLevel == null, logs.size) {
                filterLevel = null
            }
            FilterChip("ERROR", filterLevel == "E", logs.count { it.level == "E" }, Color(0xFFE53935)) {
                filterLevel = if (filterLevel == "E") null else "E"
            }
            FilterChip("WARN", filterLevel == "W", logs.count { it.level == "W" }, Color(0xFFFF9800)) {
                filterLevel = if (filterLevel == "W") null else "W"
            }
            FilterChip("INFO", filterLevel == "I", logs.count { it.level == "I" }, AiGreen) {
                filterLevel = if (filterLevel == "I") null else "I"
            }
        }

        // 统计信息
        Text(
            "共 ${logs.size} 条 (ERROR: ${logs.count { it.level == "E" }}, WARN: ${logs.count { it.level == "W" }}, INFO: ${logs.count { it.level == "I" }})",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // 日志列表
        BrutalCard(modifier = Modifier.weight(1f)) {
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("暂无日志", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "启动投屏或监听服务后，日志将在此显示",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(filtered, key = { "${it.timestamp}-${it.message}" }) { entry ->
                        LogRow(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, count: Int, color: Color = AiBlack, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = if (selected) color else Color.Transparent,
        border = if (!selected) ButtonDefaults.outlinedButtonBorder else null
    ) {
        Text(
            "$label ($count)",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            fontSize = 12.sp,
            color = if (selected) Color.White else color
        )
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
    val time = remember(entry.timestamp) { timeFormat.format(Date(entry.timestamp)) }

    val levelColor = when (entry.level) {
        "E" -> Color(0xFFE53935)
        "W" -> Color(0xFFFF9800)
        "I" -> AiGreen
        else -> MaterialTheme.colorScheme.onSurface
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        // 时间戳
        Text(
            time,
            fontSize = 10.sp,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp)
        )

        // 级别标签
        Text(
            " ${entry.level} ",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = levelColor,
            modifier = Modifier
                .background(levelColor.copy(alpha = 0.12f), MaterialTheme.shapes.extraSmall)
                .padding(horizontal = 4.dp)
        )

        // 消息内容
        Text(
            entry.message,
            fontSize = 12.sp,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}
