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
data class NotifyRequest(val amount: Double, val channel: String)

@Serializable
data class NotifyResponse(
    val matched: Boolean,
    @SerialName("order_id") val orderId: String? = null,
    val reason: String? = null
)

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

    suspend fun notify(settings: AppSettings, amount: Double, channel: String): ApiReportResult =
        withContext(Dispatchers.IO) {
            require(settings.apiKey.startsWith("aip_")) { "API Key 格式应为 aip_xxxxxxxx" }
            val body = json.encodeToString(NotifyRequest.serializer(), NotifyRequest(amount, channel))
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
}
