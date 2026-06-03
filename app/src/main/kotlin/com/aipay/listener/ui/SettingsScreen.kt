package com.aipay.listener.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aipay.listener.data.AppSettings

@Composable
fun SettingsScreen(
    settings: AppSettings,
    healthResult: String,
    onApiBaseChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onScanApiKey: () -> Unit,
    onTestConnection: () -> Unit,
    onWechatChange: (Boolean) -> Unit,
    onAlipayChange: (Boolean) -> Unit,
    onDebugModeChange: (Boolean) -> Unit,
    onMinAmountChange: (Double) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("设置", style = MaterialTheme.typography.headlineLarge)
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
            ChannelSwitch("调试模式", settings.debugMode, onDebugModeChange)
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
    }
}

@Composable
private fun ChannelSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
