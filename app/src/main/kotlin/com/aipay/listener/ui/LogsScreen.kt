package com.aipay.listener.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aipay.listener.data.PaymentLog
import com.google.accompanist.swiperefresh.SwipeRefresh
import com.google.accompanist.swiperefresh.rememberSwipeRefreshState

@Composable
fun LogsScreen(logs: List<PaymentLog>, onRefresh: () -> Unit) {
    var selected by remember { mutableStateOf<PaymentLog?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    SwipeRefresh(
        state = rememberSwipeRefreshState(refreshing),
        onRefresh = {
            refreshing = true
            onRefresh()
            refreshing = false
        }
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("日志", style = MaterialTheme.typography.headlineLarge)
                    BrutalButton("刷新", onRefresh, Modifier.fillMaxWidth(), filled = false)
                }
            }
            if (logs.isEmpty()) {
                item { BrutalCard { Text("暂无日志") } }
            } else {
                items(logs, key = { it.id }) { log ->
                    LogRow(log, Modifier.clickable { selected = log })
                }
            }
        }
    }

    selected?.let { log ->
        AlertDialog(
            onDismissRequest = { selected = null },
            confirmButton = { BrutalButton("关闭", { selected = null }) },
            title = { Text("${channelName(log.channel)} ¥${"%.2f".format(log.amount)}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("时间：${formatTime(log.timestamp)}")
                    Text("状态：${log.status}")
                    Text("解析金额：¥${"%.2f".format(log.amount)}")
                    Text("原始通知全文：\n${log.raw.ifBlank { "无" }}")
                    Text("服务器响应：\n${log.serverResponse.ifBlank { "无" }}")
                }
            },
            shape = androidx.compose.ui.graphics.RectangleShape
        )
    }
}
