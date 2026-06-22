package com.aipay.listener

import android.Manifest
import android.app.Activity
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
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
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
import com.aipay.listener.data.Order
import com.aipay.listener.data.SettingsRepository
import com.aipay.listener.service.KeepAliveService
import com.aipay.listener.service.DebugLog
import com.aipay.listener.service.PayNotificationListener
import com.aipay.listener.ui.HomeScreen
import com.aipay.listener.ui.OrdersScreen
import com.aipay.listener.ui.PermissionState
import com.aipay.listener.ui.DebugScreen
import com.aipay.listener.ui.SettingsScreen
import com.aipay.listener.ui.theme.AiPayTheme
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

class MainActivity : ComponentActivity() {
    private lateinit var settingsRepository: SettingsRepository
    private var hasNotificationAccess by mutableStateOf(false)
    private var hasPostNotificationPermission by mutableStateOf(false)
    private var isBatteryOptimizationIgnored by mutableStateOf(false)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasPostNotificationPermission = granted
        }

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@registerForActivityResult
        val key = Regex("""aip_[A-Za-z0-9_\-]+""").find(contents)?.value ?: contents
        lifecycleScope.launch { settingsRepository.updateApiKey(key) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsRepository = SettingsRepository(this)
        hasNotificationAccess = isNotificationListenerEnabled()
        hasPostNotificationPermission = checkPostNotificationPermission()
        isBatteryOptimizationIgnored = isIgnoringBatteryOptimizations()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // 启动时自动开启监听服务
        lifecycleScope.launch {
            val currentSettings = settingsRepository.settings.first()
            if (currentSettings.monitoringEnabled) {
                DebugLog.i("MainActivity", "自动启动监听服务")
                startKeepAliveService()
            }
        }

        setContent {
            AiPayTheme {
                AiPayAppContent(
                    settingsRepository = settingsRepository,
                    hasNotificationAccess = hasNotificationAccess,
                    hasPostNotificationPermission = hasPostNotificationPermission,
                    isBatteryOptimizationIgnored = isBatteryOptimizationIgnored,
                    onOpenNotificationSettings = {
                        startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                    onOpenNotificationPermissionSettings = {
                        openAppNotificationSettings()
                    },
                    onOpenBatterySettings = {
                        requestBatteryWhitelist()
                    },
                    onToggleMonitoring = { enabled ->
                        DebugLog.i("MainActivity", "监听开关: $enabled")
                        lifecycleScope.launch { settingsRepository.updateMonitoringEnabled(enabled) }
                        if (enabled) {
                            startKeepAliveService()
                        } else {
                            stopService(Intent(this, KeepAliveService::class.java))
                        }
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
        hasPostNotificationPermission = checkPostNotificationPermission()
        isBatteryOptimizationIgnored = isIgnoringBatteryOptimizations()

        // 记录监听器连接状态
        DebugLog.i("MainActivity",
            "监听器状态: created=${PayNotificationListener.isServiceCreated} connected=${PayNotificationListener.isServiceConnected} " +
            "lastConnected=${if(PayNotificationListener.lastConnectedAt > 0) "${(System.currentTimeMillis()-PayNotificationListener.lastConnectedAt)/1000}s前" else "从未"}"
        )
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

    private fun openAppNotificationSettings() {
        val intent = Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(AndroidSettings.EXTRA_APP_PACKAGE, packageName)
        }
        startActivity(intent)
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val enabled = AndroidSettings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        val component = ComponentName(this, "com.aipay.listener.service.PayNotificationListener").flattenToString()
        return enabled.split(":").any { it.equals(component, ignoreCase = true) || it.contains(packageName) }
    }

    private fun checkPostNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true // Android 13 以下不需要此权限
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = getSystemService(PowerManager::class.java)
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }
}

@Composable
private fun AiPayAppContent(
    settingsRepository: SettingsRepository,
    hasNotificationAccess: Boolean,
    hasPostNotificationPermission: Boolean,
    isBatteryOptimizationIgnored: Boolean,
    onOpenNotificationSettings: () -> Unit,
    onOpenNotificationPermissionSettings: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onToggleMonitoring: (Boolean) -> Unit,
    onScanApiKey: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by settingsRepository.settings.collectAsState(initial = AppSettings())
    val dao = remember { AppDatabase.get(context).logDao() }
    val orderDao = remember { AppDatabase.get(context).orderDao() }
    val templateDao = remember { AppDatabase.get(context).templateDao() }
    val todayStart = remember {
        LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
    val recentLogs by dao.recent(20).collectAsState(initial = emptyList())
    val allLogs by dao.all().collectAsState(initial = emptyList())
    val captured by dao.capturedCount(todayStart).collectAsState(initial = 0)
    val success by dao.countByStatus(todayStart, LogStatus.SUCCESS).collectAsState(initial = 0)
    val failed by dao.countByStatus(todayStart, LogStatus.FAILED).collectAsState(initial = 0)
    var healthResult by remember { mutableStateOf("") }
    val orders by orderDao.today(todayStart).collectAsState(initial = emptyList())
    val templates by templateDao.all().collectAsState(initial = emptyList())
    var isLoadingOrders by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val navController = rememberNavController()
    val items = listOf(Screen.Home, Screen.Orders, Screen.Settings, Screen.Debug)

    // 权限状态列表
    val permissionStates = remember(hasNotificationAccess, hasPostNotificationPermission, isBatteryOptimizationIgnored) {
        listOf(
            PermissionState(
                name = "通知监听服务",
                description = "允许读取微信/支付宝收款通知",
                granted = hasNotificationAccess,
                onOpenSettings = onOpenNotificationSettings
            ),
            PermissionState(
                name = "通知权限",
                description = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) "Android 13+ 需要此权限发送通知" else "已自动获得",
                granted = hasPostNotificationPermission,
                onOpenSettings = onOpenNotificationPermissionSettings
            ),
            PermissionState(
                name = "电池优化白名单",
                description = "避免系统杀掉后台服务，确保稳定运行",
                granted = isBatteryOptimizationIgnored,
                onOpenSettings = onOpenBatterySettings
            )
        )
    }

    val loadOrders: suspend () -> Unit = {
        if (settings.apiKey.isNotBlank()) {
            isLoadingOrders = true
            runCatching {
                val fetched = ApiClient().fetchOrders(settings)
                orderDao.insertAll(fetched)
                loadError = null
            }.onFailure { e ->
                loadError = "拉取失败：${e.message}"
                android.util.Log.w("AiPay", "拉取订单失败: ${e.message}")
            }
            isLoadingOrders = false
        }
    }

    // 定期检测服务器连接状态
    var serverOnline by remember { mutableStateOf(false) }
    LaunchedEffect(settings.apiBaseUrl) {
        while (true) {
            serverOnline = runCatching {
                ApiClient().health(settings.apiBaseUrl, settings.apiKey)
                true
            }.getOrDefault(false)
            kotlinx.coroutines.delay(10_000)
        }
    }

    // 定期读取监听器连接状态（静态变量变化不会自动触发 Compose 重组）
    var listenerConnected by remember { mutableStateOf(PayNotificationListener.isServiceConnected) }
    var listenerLastConnectedAt by remember { mutableStateOf(PayNotificationListener.lastConnectedAt) }
    LaunchedEffect(Unit) {
        while (true) {
            listenerConnected = PayNotificationListener.isServiceConnected
            listenerLastConnectedAt = PayNotificationListener.lastConnectedAt
            kotlinx.coroutines.delay(2000)
        }
    }

    // 每次切到订单页时自动加载
    val backStackEntry by navController.currentBackStackEntryAsState()
    androidx.compose.runtime.LaunchedEffect(backStackEntry?.destination?.route) {
        if (backStackEntry?.destination?.route == Screen.Orders.route
            && settings.apiKey.isNotBlank()
        ) {
            loadOrders()
        }
    }

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
            composable(Screen.Orders.route) {
                OrdersScreen(
                    settings = settings,
                    orders = orders,
                    notifications = allLogs.take(50),
                    isLoading = isLoadingOrders,
                    loadError = loadError,
                    onLoadOrders = loadOrders
                )
            }
            composable(Screen.Home.route) {
                HomeScreen(
                    settings = settings,
                    hasNotificationAccess = hasNotificationAccess,
                    isListenerConnected = listenerConnected,
                    lastConnectedAt = listenerLastConnectedAt,
                    serverOnline = serverOnline,
                    recentLogs = recentLogs,
                    captured = captured,
                    success = success,
                    failed = failed,
                    onToggleMonitoring = onToggleMonitoring,
                    onOpenNotificationSettings = onOpenNotificationSettings,
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    settings = settings,
                    permissions = permissionStates,
                    healthResult = healthResult,
                    templates = templates,
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
                    onMinAmountChange = { scope.launch { settingsRepository.updateMinAmount(it) } },
                    onAddTemplate = { scope.launch { templateDao.insert(it) } },
                    onUpdateTemplate = { scope.launch { templateDao.update(it) } },
                    onDeleteTemplate = { scope.launch { templateDao.delete(it) } },
                    onToggleTemplate = { template, enabled ->
                        scope.launch { templateDao.update(template.copy(enabled = enabled)) }
                    }
                )
            }
            composable(Screen.Debug.route) {
                DebugScreen()
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
    data object Orders : Screen("orders", "订单", Icons.Default.Receipt)
    data object Settings : Screen("settings", "设置", Icons.Default.Settings)
    data object Debug : Screen("debug", "调试", Icons.Default.Star)
}
