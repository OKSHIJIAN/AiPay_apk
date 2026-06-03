package com.aipay.listener

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aipay.listener.data.ApiClient
import com.aipay.listener.data.AppDatabase
import com.aipay.listener.data.AppSettings
import com.aipay.listener.data.LogStatus
import com.aipay.listener.data.SettingsRepository
import com.aipay.listener.service.KeepAliveService
import com.aipay.listener.ui.HomeScreen
import com.aipay.listener.ui.LogsScreen
import com.aipay.listener.ui.SettingsScreen
import com.aipay.listener.ui.theme.AiPayTheme
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

class MainActivity : ComponentActivity() {
    private lateinit var settingsRepository: SettingsRepository
    private var hasNotificationAccess by mutableStateOf(false)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@registerForActivityResult
        val key = Regex("""aip_[A-Za-z0-9_\-]+""").find(contents)?.value ?: contents
        lifecycleScope.launch { settingsRepository.updateApiKey(key) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsRepository = SettingsRepository(this)
        hasNotificationAccess = isNotificationListenerEnabled()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            AiPayTheme {
                AiPayAppContent(
                    settingsRepository = settingsRepository,
                    hasNotificationAccess = hasNotificationAccess,
                    onOpenNotificationSettings = {
                        startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                    onToggleMonitoring = { enabled ->
                        lifecycleScope.launch { settingsRepository.updateMonitoringEnabled(enabled) }
                        if (enabled) startKeepAliveService() else stopService(Intent(this, KeepAliveService::class.java))
                    },
                    onScanApiKey = {
                        scanLauncher.launch(
                            ScanOptions()
                                .setPrompt("扫描 API Key")
                                .setBeepEnabled(false)
                                .setDesiredBarcodeFormats(listOf(ScanOptions.QR_CODE))
                        )
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hasNotificationAccess = isNotificationListenerEnabled()
    }

    private fun startKeepAliveService() {
        requestBatteryWhitelist()
        val intent = Intent(this, KeepAliveService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
    }

    private fun requestBatteryWhitelist() {
        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return
        runCatching {
            startActivity(
                Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val enabled = AndroidSettings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        val component = ComponentName(this, "com.aipay.listener.service.PayNotificationListener").flattenToString()
        return enabled.split(":").any { it.equals(component, ignoreCase = true) || it.contains(packageName) }
    }
}

@Composable
private fun AiPayAppContent(
    settingsRepository: SettingsRepository,
    hasNotificationAccess: Boolean,
    onOpenNotificationSettings: () -> Unit,
    onToggleMonitoring: (Boolean) -> Unit,
    onScanApiKey: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by settingsRepository.settings.collectAsState(initial = AppSettings())
    val dao = remember { AppDatabase.get(context).logDao() }
    val todayStart = remember {
        LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
    val recentLogs by dao.recent(20).collectAsState(initial = emptyList())
    val allLogs by dao.all().collectAsState(initial = emptyList())
    val captured by dao.capturedCount(todayStart).collectAsState(initial = 0)
    val success by dao.countByStatus(todayStart, LogStatus.SUCCESS).collectAsState(initial = 0)
    val failed by dao.countByStatus(todayStart, LogStatus.FAILED).collectAsState(initial = 0)
    var healthResult by remember { mutableStateOf("") }
    val navController = rememberNavController()
    val items = listOf(Screen.Home, Screen.Settings, Screen.Logs)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                val backStack by navController.currentBackStackEntryAsState()
                val current = backStack?.destination
                items.forEach { screen ->
                    NavigationBarItem(
                        selected = current?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = { navController.navigate(screen.route) { launchSingleTop = true } },
                        icon = { Icon(screen.icon, contentDescription = screen.label) },
                        label = { Text(screen.label) }
                    )
                }
            }
        }
    ) { paddingValues ->
        NavHost(navController, startDestination = Screen.Home.route, modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            composable(Screen.Home.route) {
                HomeScreen(
                    settings = settings,
                    hasNotificationAccess = hasNotificationAccess,
                    recentLogs = recentLogs,
                    captured = captured,
                    success = success,
                    failed = failed,
                    onToggleMonitoring = onToggleMonitoring,
                    onOpenNotificationSettings = onOpenNotificationSettings
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    settings = settings,
                    healthResult = healthResult,
                    onApiBaseChange = { scope.launch { settingsRepository.updateApiBaseUrl(it) } },
                    onApiKeyChange = { scope.launch { settingsRepository.updateApiKey(it) } },
                    onScanApiKey = onScanApiKey,
                    onTestConnection = {
                        scope.launch {
                            healthResult = "测试中..."
                            healthResult = runCatching { ApiClient().health(settings.apiBaseUrl, settings.apiKey) }
                                .fold(onSuccess = { "连接成功：$it" }, onFailure = { "连接失败：${it.message}" })
                        }
                    },
                    onWechatChange = { scope.launch { settingsRepository.updateListenWechat(it) } },
                    onAlipayChange = { scope.launch { settingsRepository.updateListenAlipay(it) } },
                    onDebugModeChange = { scope.launch { settingsRepository.updateDebugMode(it) } },
                    onMinAmountChange = { scope.launch { settingsRepository.updateMinAmount(it) } }
                )
            }
            composable(Screen.Logs.route) {
                LogsScreen(logs = allLogs, onRefresh = {})
            }
        }
    }
}

private sealed class Screen(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    data object Home : Screen("home", "首页", Icons.Default.Home)
    data object Settings : Screen("settings", "设置", Icons.Default.Settings)
    data object Logs : Screen("logs", "日志", Icons.Default.List)
}
