package com.aipay.listener.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore("settings")

const val DEFAULT_API_BASE = "https://qlsmtkqdvbionwpmhoyu.supabase.co/functions/v1/make-server-41dc007f"

data class AppSettings(
    val apiBaseUrl: String = DEFAULT_API_BASE,
    val apiKey: String = "",
    val listenWechat: Boolean = true,
    val listenAlipay: Boolean = true,
    val minAmount: Double = 0.0,
    val monitoringEnabled: Boolean = true
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val apiBaseUrl = stringPreferencesKey("api_base_url")
        val apiKey = stringPreferencesKey("api_key")
        val listenWechat = booleanPreferencesKey("listen_wechat")
        val listenAlipay = booleanPreferencesKey("listen_alipay")
        val minAmount = doublePreferencesKey("min_amount")
        val monitoringEnabled = booleanPreferencesKey("monitoring_enabled")
    }

    val settings: Flow<AppSettings> = context.settingsStore.data.map { prefs ->
        AppSettings(
            apiBaseUrl = prefs[Keys.apiBaseUrl] ?: DEFAULT_API_BASE,
            apiKey = prefs[Keys.apiKey] ?: "",
            listenWechat = prefs[Keys.listenWechat] ?: true,
            listenAlipay = prefs[Keys.listenAlipay] ?: true,
            minAmount = prefs[Keys.minAmount] ?: 0.0,
            monitoringEnabled = prefs[Keys.monitoringEnabled] ?: true
        )
    }

    suspend fun updateApiBaseUrl(value: String) = updateString(Keys.apiBaseUrl, value.trim())
    suspend fun updateApiKey(value: String) = updateString(Keys.apiKey, value.trim())
    suspend fun updateListenWechat(value: Boolean) = updateBoolean(Keys.listenWechat, value)
    suspend fun updateListenAlipay(value: Boolean) = updateBoolean(Keys.listenAlipay, value)
    suspend fun updateMinAmount(value: Double) = context.settingsStore.edit { it[Keys.minAmount] = value.coerceAtLeast(0.0) }
    suspend fun updateMonitoringEnabled(value: Boolean) = updateBoolean(Keys.monitoringEnabled, value)

    private suspend fun updateString(key: androidx.datastore.preferences.core.Preferences.Key<String>, value: String) {
        context.settingsStore.edit { it[key] = value }
    }

    private suspend fun updateBoolean(key: androidx.datastore.preferences.core.Preferences.Key<Boolean>, value: Boolean) {
        context.settingsStore.edit { it[key] = value }
    }
}
