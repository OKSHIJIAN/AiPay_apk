package com.aipay.listener.data

import com.aipay.listener.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
data class NotifyRequest(val amount: Double, val channel: String, val uptime: Long = 0)

@Serializable
data class NotifyResponse(
    val matched: Boolean,
    @SerialName("order_id") val orderId: String? = null,
    val reason: String? = null
)

@Serializable
data class OrderDto(
    val id: String,
    val amount: Double,
    val status: String,
    val channel: String = "",
    val createdAt: Long? = null
)

@Serializable
data class KvStringEntry(val value: String)

@Serializable
data class KvOrderEntry(val value: OrderDto)

data class ApiReportResult(val status: String, val response: String)

class ApiClient {
    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("Authorization", "Bearer ${BuildConfig.SUPABASE_ANON_KEY}")
                .build()
            chain.proceed(request)
        }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun health(baseUrl: String, apiKey: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/health")
            .header("X-Api-Key", apiKey)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}: $body")
            body
        }
    }

    /** 心跳上报：让服务器知道手机在线，网页端「支付监听」页可看到真实在线状态 */
    suspend fun heartbeat(settings: AppSettings, uptime: Long): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val body = "{\"uptime\":$uptime}".toRequestBody(mediaType)
            val request = Request.Builder()
                .url("${settings.apiBaseUrl.trimEnd('/')}/heartbeat")
                .header("X-Api-Key", settings.apiKey)
                .header("Content-Type", "application/json")
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            }
            true
        }.getOrDefault(false)
    }

    suspend fun notify(settings: AppSettings, amount: Double, channel: String, uptime: Long = 0): ApiReportResult =
        withContext(Dispatchers.IO) {
            require(settings.apiKey.startsWith("aip_")) { "API Key 格式应为 aip_xxxxxxxx" }
            val body = json.encodeToString(NotifyRequest.serializer(), NotifyRequest(amount, channel, uptime))
                .toRequestBody(mediaType)
            val request = Request.Builder()
                .url("${settings.apiBaseUrl.trimEnd('/')}/notify")
                .header("X-Api-Key", settings.apiKey)
                .header("Content-Type", "application/json")
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}: $responseBody")
                val parsed = json.decodeFromString(NotifyResponse.serializer(), responseBody)
                ApiReportResult(
                    status = if (parsed.matched) LogStatus.SUCCESS else LogStatus.UNMATCHED,
                    response = responseBody
                )
            }
        }

    suspend fun fetchOrders(settings: AppSettings): List<Order> = withContext(Dispatchers.IO) {
        val supabaseRef = "qlsmtkqdvbionwpmhoyu"
        val kvUrl = "https://$supabaseRef.supabase.co/rest/v1/kv_store_41dc007f"
        val anonKey = BuildConfig.SUPABASE_ANON_KEY

        // Step 1: 从 API Key 解析 merchantId
        val merchantIdReq = Request.Builder()
            .url("$kvUrl?select=value&key=eq.${java.net.URLEncoder.encode("apikey:${settings.apiKey}", "UTF-8")}")
            .header("apikey", anonKey)
            .get()
            .build()
        val merchantId = client.newCall(merchantIdReq).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("查询账户失败 HTTP ${response.code}: $body")
            val parsed = json.decodeFromString<List<KvStringEntry>>(body)
            parsed.firstOrNull()?.value
                ?: throw IOException("未找到 API Key 对应的账户")
        }

        // Step 2: 查询该商户的所有订单
        val ordersReq = Request.Builder()
            .url("$kvUrl?select=value&key=like.${java.net.URLEncoder.encode("order:$merchantId:*", "UTF-8")}")
            .header("apikey", anonKey)
            .get()
            .build()
        client.newCall(ordersReq).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("查询订单失败 HTTP ${response.code}: $body")
            val parsed = json.decodeFromString<List<KvOrderEntry>>(body)
            parsed.map { entry ->
                val dto = entry.value
                Order(
                    orderId = dto.id,
                    amount = dto.amount,
                    status = dto.status,
                    channel = dto.channel,
                    createdAt = dto.createdAt ?: System.currentTimeMillis(),
                    paidAt = null,
                    description = ""
                )
            }
        }
    }
}
