package com.aipay.listener.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.aipay.listener.data.PaymentLog
import com.aipay.listener.ui.theme.AiBlack
import com.aipay.listener.ui.theme.AiMuted
import com.aipay.listener.ui.theme.AiPaper
import com.aipay.listener.ui.theme.AiYellow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BrutalCard(modifier: Modifier = Modifier, background: Color = AiPaper, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.border(1.5.dp, AiBlack),
        shape = androidx.compose.ui.graphics.RectangleShape,
        colors = CardDefaults.cardColors(containerColor = background),
        elevation = CardDefaults.cardElevation(0.dp),
        content = { Column(Modifier.padding(14.dp)) { content() } }
    )
}

@Composable
fun BrutalButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, filled: Boolean = true) {
    if (filled) {
        Button(
            onClick = onClick,
            modifier = modifier,
            shape = androidx.compose.ui.graphics.RectangleShape,
            colors = ButtonDefaults.buttonColors(containerColor = AiBlack, contentColor = AiPaper)
        ) { Text(text) }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            shape = androidx.compose.ui.graphics.RectangleShape,
            border = BorderStroke(1.5.dp, AiBlack),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = AiBlack)
        ) { Text(text) }
    }
}

@Composable
fun StatBox(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(AiMuted)
            .border(1.5.dp, AiBlack)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(value, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
fun LogRow(log: PaymentLog, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .border(1.5.dp, AiBlack)
            .padding(10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(formatTime(log.timestamp), style = MaterialTheme.typography.labelLarge)
            Text("${channelName(log.channel)}  ¥${"%.2f".format(log.amount)}", style = MaterialTheme.typography.titleMedium)
        }
        Text(log.status, style = MaterialTheme.typography.labelLarge)
    }
}

fun formatTime(timestamp: Long): String =
    SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(timestamp))

fun channelName(channel: String): String = when (channel) {
    "wechat" -> "微信"
    "alipay" -> "支付宝"
    else -> channel
}
