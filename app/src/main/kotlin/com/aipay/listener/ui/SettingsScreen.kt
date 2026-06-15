package com.aipay.listener.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aipay.listener.data.AmountMarkData
import com.aipay.listener.data.AppSettings
import com.aipay.listener.data.NotificationTemplate
import com.aipay.listener.ui.theme.AiBlack
import com.aipay.listener.ui.theme.AiGreen
import com.aipay.listener.ui.theme.AiMuted
import com.aipay.listener.ui.theme.AiPaper

data class PermissionState(
    val name: String,
    val description: String,
    val granted: Boolean,
    val onOpenSettings: () -> Unit
)

@Composable
fun SettingsScreen(
    settings: AppSettings,
    healthResult: String,
    permissions: List<PermissionState>,
    templates: List<NotificationTemplate>,
    onApiBaseChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onScanApiKey: () -> Unit,
    onTestConnection: () -> Unit,
    onWechatChange: (Boolean) -> Unit,
    onAlipayChange: (Boolean) -> Unit,
    onMinAmountChange: (Double) -> Unit,
    onAddTemplate: (NotificationTemplate) -> Unit,
    onUpdateTemplate: (NotificationTemplate) -> Unit,
    onDeleteTemplate: (NotificationTemplate) -> Unit,
    onToggleTemplate: (NotificationTemplate, Boolean) -> Unit
) {
    var editingTemplate by remember { mutableStateOf<NotificationTemplate?>(null) }
    var showDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("设置", style = MaterialTheme.typography.headlineLarge)

        BrutalCard {
            Text("权限管理", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 10.dp))
            permissions.forEach { permission ->
                PermissionItem(permission)
            }
        }

        BrutalCard {
            OutlinedTextField(
                value = settings.apiBaseUrl,
                onValueChange = onApiBaseChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API Base URL") },
                singleLine = false
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = settings.apiKey,
                    onValueChange = onApiKeyChange,
                    modifier = Modifier.weight(1f),
                    label = { Text("API Key") },
                    singleLine = true
                )
                IconButton(onClick = onScanApiKey) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = "扫码填入")
                }
            }
            BrutalButton("测试连接", onTestConnection, Modifier.fillMaxWidth().padding(top = 8.dp))
            if (healthResult.isNotBlank()) Text(healthResult, Modifier.padding(top = 8.dp))
        }

        BrutalCard {
            ChannelSwitch("微信", settings.listenWechat, onWechatChange)
            ChannelSwitch("支付宝", settings.listenAlipay, onAlipayChange)
        }

        BrutalCard {
            OutlinedTextField(
                value = if (settings.minAmount == 0.0) "" else settings.minAmount.toString(),
                onValueChange = { onMinAmountChange(it.toDoubleOrNull() ?: 0.0) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("最小金额阈值") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true
            )
        }

        // 捕获通知模板
        BrutalCard {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("捕获通知模板", style = MaterialTheme.typography.titleMedium)
                BrutalButton("+ 添加", onClick = {
                    editingTemplate = null
                    showDialog = true
                }, filled = false)
            }

            if (templates.isEmpty()) {
                Text(
                    "尚未添加模板。点击 + 添加 可自定义捕获任意 App 的收款通知。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                templates.forEach { template ->
                    TemplateItem(
                        template = template,
                        onEdit = {
                            editingTemplate = template
                            showDialog = true
                        },
                        onDelete = { onDeleteTemplate(template) },
                        onToggle = { enabled -> onToggleTemplate(template, enabled) }
                    )
                }
            }
        }
    }

    // 添加/编辑模板对话框
    if (showDialog) {
        TemplateDialog(
            template = editingTemplate,
            onDismiss = { showDialog = false },
            onSave = { template ->
                if (editingTemplate != null) {
                    onUpdateTemplate(template)
                } else {
                    onAddTemplate(template)
                }
                showDialog = false
            }
        )
    }
}

@Composable
private fun TemplateItem(
    template: NotificationTemplate,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        template.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!template.enabled) {
                        Text(
                            " · 已禁用",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFFF9800)
                        )
                    }
                }
                Text(
                    template.channelName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Switch(checked = template.enabled, onCheckedChange = onToggle)
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "编辑", modifier = Modifier.padding(end = 4.dp))
                Text("编辑")
            }
            TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFE53935))) {
                Icon(Icons.Default.Delete, contentDescription = "删除", modifier = Modifier.padding(end = 4.dp))
                Text("删除")
            }
        }
    }
}

@Composable
private fun TemplateDialog(
    template: NotificationTemplate?,
    onDismiss: () -> Unit,
    onSave: (NotificationTemplate) -> Unit
) {
    val isEdit = template != null
    var name by remember { mutableStateOf(template?.name ?: "") }
    var titleKeyword by remember { mutableStateOf(template?.titleKeyword ?: "") }
    var contentKeyword by remember { mutableStateOf(template?.contentKeyword ?: "") }
    var amountMarked by remember { mutableStateOf(template?.amountMarked ?: "") }
    var amountMarkData by remember { mutableStateOf(template?.getAmountMarkData()) }
    var showMarker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) "编辑模板" else "添加模板") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("软件名") },
                    placeholder = { Text("如：美团外卖、钉钉") },
                    singleLine = true,
                    supportingText = { Text("给模板起个好记的名字") }
                )
                OutlinedTextField(
                    value = titleKeyword,
                    onValueChange = { titleKeyword = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("标题关键词 *") },
                    placeholder = { Text("通知标题包含这些词才会触发，如：收款,到账") },
                    singleLine = true,
                    supportingText = { Text("多个关键词用逗号分隔") }
                )
                OutlinedTextField(
                    value = contentKeyword,
                    onValueChange = { contentKeyword = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("内容关键词（可选）") },
                    placeholder = { Text("对通知内容做二次过滤，留空则不检查") },
                    singleLine = true
                )

                // 金额标记
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "金额标记",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold
                        )
                        val markData = amountMarkData
                        Text(
                            if (markData != null) "已标记 · ${markData.markedPositions.size} 个字符"
                            else "可选：帮助准确提取金额",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (markData != null) AiGreen else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    BrutalButton(
                        text = if (amountMarkData != null) "重新标记" else "标记金额",
                        onClick = { showMarker = true },
                        filled = false
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isBlank() || titleKeyword.isBlank()) return@TextButton
                    onSave(
                        NotificationTemplate(
                            id = template?.id ?: 0,
                            name = name.trim(),
                            titleKeyword = titleKeyword.trim(),
                            contentKeyword = contentKeyword.trim(),
                            amountMarked = amountMarked,
                            enabled = template?.enabled ?: true,
                            channelName = name.trim(),
                            createdAt = template?.createdAt ?: System.currentTimeMillis()
                        )
                    )
                },
                enabled = name.isNotBlank() && titleKeyword.isNotBlank()
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )

    if (showMarker) {
        AmountMarkerDialog(
            initialData = amountMarkData,
            onDismiss = { showMarker = false },
            onConfirm = { markData ->
                amountMarkData = markData
                amountMarked = NotificationTemplate.encodeAmountMarkData(markData)
                showMarker = false
            }
        )
    }
}

@Composable
private fun PermissionItem(permission: PermissionState) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .padding(start = 4.dp, end = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (permission.granted) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (permission.granted) Color(0xFF4CAF50) else Color(0xFFFF9800)
            )
            Column {
                Text(permission.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    permission.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (!permission.granted) {
            BrutalButton("开通", permission.onOpenSettings, filled = false)
        } else {
            Text("已授权", style = MaterialTheme.typography.labelMedium, color = Color(0xFF4CAF50))
        }
    }
}

@Composable
private fun ChannelSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
