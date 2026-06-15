package com.aipay.listener.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aipay.listener.data.AmountMarkData
import com.aipay.listener.ui.theme.AiBlack
import com.aipay.listener.ui.theme.AiGreen
import com.aipay.listener.ui.theme.AiMuted
import com.aipay.listener.ui.theme.AiYellow

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AmountMarkerDialog(
    initialData: AmountMarkData?,
    onDismiss: () -> Unit,
    onConfirm: (AmountMarkData) -> Unit
) {
    var sampleText by remember { mutableStateOf(initialData?.sampleText ?: "") }
    val selectedPositions = remember {
        mutableStateListOf<Int>().apply {
            initialData?.markedPositions?.let { addAll(it) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("金额标记") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "粘贴一条示例通知文本，然后点击下方构成金额的字符。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = sampleText,
                    onValueChange = {
                        sampleText = it
                        selectedPositions.clear()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("示例通知文本") },
                    placeholder = { Text("粘贴如：收款通知，来自张三的转账128.00元，余额2000.00") },
                    minLines = 2,
                    maxLines = 5
                )

                if (sampleText.isNotBlank()) {
                    Text(
                        "点击字符选中/取消：",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        sampleText.forEachIndexed { index, char ->
                            val isSelected = index in selectedPositions
                            val bgColor = when {
                                isSelected -> AiYellow
                                char == '\n' || char == '\r' -> Color.Transparent
                                else -> AiMuted
                            }

                            if (char == '\n' || char == '\r') {
                                // 换行不显示但占位
                                return@forEachIndexed
                            }

                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 1.dp, vertical = 2.dp)
                                    .size(36.dp)
                                    .clip(MaterialTheme.shapes.small)
                                    .background(bgColor)
                                    .border(
                                        width = 1.dp,
                                        color = if (isSelected) AiBlack else Color.Transparent
                                    )
                                    .clickable {
                                        if (isSelected) {
                                            selectedPositions.remove(index)
                                        } else {
                                            selectedPositions.add(index)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = char.toString(),
                                    fontSize = 15.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) AiBlack else MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    // 选中金额预览
                    if (selectedPositions.isNotEmpty()) {
                        val sorted = selectedPositions.sorted()
                        val markedText = sorted.map { sampleText[it] }.joinToString("")
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(AiGreen.copy(alpha = 0.1f))
                                .padding(10.dp)
                        ) {
                            Text(
                                "已标记金额：$markedText",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = AiGreen
                            )
                            Text(
                                "系统将依据左右上下文自动定位金额",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (sampleText.isBlank() || selectedPositions.isEmpty()) return@TextButton
                    val sorted = selectedPositions.sorted()
                    val firstIdx = sorted.first()
                    val lastIdx = sorted.last()

                    // 计算左右上下文（取前后各最多5个字符）
                    val leftCtx = if (firstIdx > 0) {
                        val start = (firstIdx - 5).coerceAtLeast(0)
                        sampleText.substring(start, firstIdx)
                    } else ""

                    val rightCtx = if (lastIdx < sampleText.length - 1) {
                        val end = (lastIdx + 1 + 5).coerceAtMost(sampleText.length)
                        sampleText.substring(lastIdx + 1, end)
                    } else ""

                    onConfirm(
                        AmountMarkData(
                            sampleText = sampleText,
                            markedPositions = sorted,
                            leftContext = leftCtx,
                            rightContext = rightCtx
                        )
                    )
                },
                enabled = sampleText.isNotBlank() && selectedPositions.isNotEmpty()
            ) {
                Text("确认标记")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    selectedPositions.clear()
                }) {
                    Text("清除", color = Color(0xFFE53935))
                }
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
        }
    )
}
