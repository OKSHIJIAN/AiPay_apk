package com.aipay.listener.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import com.aipay.listener.data.AppSettings
import com.aipay.listener.data.LogStatus
import com.aipay.listener.data.Order
import com.aipay.listener.data.OrderStatus
import com.aipay.listener.data.PaymentLog
import com.google.accompanist.swiperefresh.SwipeRefresh
import com.google.accompanist.swiperefresh.rememberSwipeRefreshState
import kotlinx.coroutines.launch
import com.aipay.listener.ui.theme.AiBlack
import com.aipay.listener.ui.theme.AiGreen
import com.aipay.listener.ui.theme.AiMuted
import com.aipay.listener.ui.theme.AiYellow

@Composable
fun OrdersScreen(
    settings: AppSettings,
    orders: List<Order>,
    notifications: List<PaymentLog>,
    isLoading: Boolean,
    loadError: String?,
    onLoadOrders: suspend () -> Unit,
) {
    var selectedOrder by remember { mutableStateOf<Order?>(null) }
    var selectedNotification by remember { mutableStateOf<PaymentLog?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    SwipeRefresh(
        state = rememberSwipeRefreshState(isRefreshing = refreshing),
        onRefresh = {
            scope.launch {
                refreshing = true
                onLoadOrders()
                refreshing = false
            }
        }
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Text("订单 / 通知", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(
                    Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OrderColumnHeader("网站订单", orders.size, AiYellow)
                    if (isLoading && orders.isEmpty()) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else if (orders.isEmpty()) {
                        BrutalCard(Modifier.weight(1f).fillMaxWidth()) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                if (loadError != null) {
                                    Text("请求失败", style = MaterialTheme.typography.titleMedium, color = Color.Red)
                                    Spacer(Modifier.height(4.dp))
                                    Text(loadError, style = MaterialTheme.typography.bodySmall, color = AiMuted)
                                    Spacer(Modifier.height(8.dp))
                                    Text("下拉刷新重试", style = MaterialTheme.typography.labelLarge, color = AiYellow)
                                } else {
                                    Text("暂无订单", style = MaterialTheme.typography.titleMedium)
                                    Spacer(Modifier.height(4.dp))
                                    Text("下拉刷新获取", style = MaterialTheme.typography.labelLarge, color = AiMuted)
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(orders, key = { it.orderId }) { order ->
                                OrderItemCard(order, Modifier.clickable { selectedOrder = order })
                            }
                        }
                    }
                }

                Column(
                    Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OrderColumnHeader("收款通知", notifications.size, AiGreen)
                    if (notifications.isEmpty()) {
                        BrutalCard(Modifier.weight(1f).fillMaxWidth()) { Text("等待捕获通知...") }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(notifications, key = { it.id }) { notif ->
                                NotificationItemCard(notif, Modifier.clickable { selectedNotification = notif })
                            }
                        }
                    }
                }
            }
        }
    }

    selectedOrder?.let { order ->
        AlertDialog(
            onDismissRequest = { selectedOrder = null },
            confirmButton = { BrutalButton("关闭", { selectedOrder = null }) },
            title = { Text("订单 ${order.orderId}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("金额：¥${"%.2f".format(order.amount)}")
                    Text("状态：${orderStatusLabel(order.status)}")
                    Text("渠道：${channelName(order.channel).ifBlank { "未指定" }}")
                    Text("创建时间：${formatTime(order.createdAt)}")
                    order.paidAt?.let { Text("支付时间：${formatTime(it)}") }
                    if (order.description.isNotBlank()) Text("备注：${order.description}")
                    if (order.notifyRaw.isNotBlank()) Text("匹配通知：\n${order.notifyRaw}")
                }
            },
            shape = RectangleShape
        )
    }

    selectedNotification?.let { log ->
        AlertDialog(
            onDismissRequest = { selectedNotification = null },
            confirmButton = { BrutalButton("关闭", { selectedNotification = null }) },
            title = { Text("${channelName(log.channel)} ¥${"%.2f".format(log.amount)}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("时间：${formatTime(log.timestamp)}")
                    Text("状态：${log.status}")
                    Text("解析金额：¥${"%.2f".format(log.amount)}")
                    Text("标题：${log.title.ifBlank { "无" }}")
                    Text("内容：${log.text.ifBlank { "无" }}")
                    Text("原始通知全文：\n${log.raw.ifBlank { "无" }}")
                    Text("服务器响应：\n${log.serverResponse.ifBlank { "无" }}")
                }
            },
            shape = RectangleShape
        )
    }
}

@Composable
private fun OrderColumnHeader(title: String, count: Int, color: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(color)
            .border(1.5.dp, AiBlack)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = AiBlack)
        Text("$count 条", style = MaterialTheme.typography.labelLarge, color = AiBlack)
    }
}

@Composable
private fun OrderItemCard(order: Order, modifier: Modifier = Modifier) {
    val statusColor = when (order.status) {
        "paid" -> AiGreen
        "pending" -> AiYellow
        else -> AiMuted
    }
    val statusBgColor = when (order.status) {
        "paid" -> Color(0x331F9D55)
        "pending" -> Color(0x33D9FB50)
        else -> Color(0x33E3E3DC)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .border(1.5.dp, AiBlack)
            .background(statusBgColor)
            .padding(10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(formatTime(order.createdAt), style = MaterialTheme.typography.labelLarge)
            Text("${order.orderId.takeLast(8)}..", style = MaterialTheme.typography.titleMedium)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("¥${"%.2f".format(order.amount)}", style = MaterialTheme.typography.titleMedium)
            Text(orderStatusLabel(order.status), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun NotificationItemCard(log: PaymentLog, modifier: Modifier = Modifier) {
    val statusColor = when (log.status) {
        LogStatus.SUCCESS -> AiGreen
        LogStatus.FAILED -> Color.Red
        LogStatus.REPORTING -> AiYellow
        else -> AiMuted
    }
    val statusBgColor = when (log.status) {
        LogStatus.SUCCESS -> Color(0x261F9D55)
        LogStatus.FAILED -> Color(0x26FF0000)
        LogStatus.REPORTING -> Color(0x26D9FB50)
        else -> Color(0x26E3E3DC)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .border(1.5.dp, AiBlack)
            .background(statusBgColor)
            .padding(10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(formatTime(log.timestamp), style = MaterialTheme.typography.labelLarge)
            Text(channelName(log.channel), style = MaterialTheme.typography.bodyMedium)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("¥${"%.2f".format(log.amount)}", style = MaterialTheme.typography.titleMedium)
            Text(log.status, style = MaterialTheme.typography.labelLarge)
        }
    }
}

private fun orderStatusLabel(status: String): String = when (status) {
    "pending" -> OrderStatus.PENDING
    "paid" -> OrderStatus.PAID
    "expired" -> OrderStatus.EXPIRED
    "cancelled" -> OrderStatus.CANCELLED
    else -> status
}
