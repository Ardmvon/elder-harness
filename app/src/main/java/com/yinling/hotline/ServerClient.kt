package com.yinling.hotline

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** One thing the circle sent to this phone. */
data class PendingMessage(
    val id: Int,
    val kind: String,
    val title: String,
    val body: String,
    val from: String,
)

/** Why a call to the server did not happen, in words a person could be shown. */
class ServerError(message: String) : Exception(message)

/**
 * The phone's side of the trusted-circle server.
 *
 * Two calls matter: a heartbeat, which is both "I am still here" and the phone's inbox, and an event,
 * which is the phone's news (报平安 / 求助 / 办好了 / 异常). No account, no password: the phone holds a
 * token it got once when the family set it up, kept encrypted in [SecretStore].
 *
 * Plain HTTP is allowed only for loopback, so a local server can be used while developing; anything
 * else has to be HTTPS, because this traffic carries an elder's name and what they were doing.
 */
class ServerClient(private val app: HotlineApp) {

    private val prefs = app.getSharedPreferences("hotline", 0)

    var baseUrl: String
        get() = prefs.getString("server_url", "").orEmpty().trimEnd('/')
        set(value) {
            prefs.edit().putString("server_url", value.trim().trimEnd('/')).apply()
        }

    /** Kept encrypted: it is the credential that lets this phone post events. */
    var token: String
        get() = SecretStore.load(app, SecretStore.DEVICE_TOKEN)
        set(value) = SecretStore.save(app, value, SecretStore.DEVICE_TOKEN)

    var pairCode: String
        get() = prefs.getString("pair_code", "").orEmpty()
        set(value) {
            prefs.edit().putString("pair_code", value).apply()
        }

    var elderName: String
        get() = prefs.getString("elder_name", "").orEmpty()
        set(value) {
            prefs.edit().putString("elder_name", value.trim()).apply()
        }

    var heartbeatSeconds: Int
        get() = prefs.getInt("heartbeat_seconds", 300)
        private set(value) {
            prefs.edit().putInt("heartbeat_seconds", value).apply()
        }

    var lastHeartbeatAt: Long
        get() = prefs.getLong("last_heartbeat_at", 0L)
        private set(value) {
            prefs.edit().putLong("last_heartbeat_at", value).apply()
        }

    var lastResult: String
        get() = prefs.getString("server_last_result", "").orEmpty()
        private set(value) {
            prefs.edit().putString("server_last_result", value).apply()
        }

    fun isConfigured(): Boolean = baseUrl.isNotBlank() && token.isNotBlank()

    /** Where the family types the pairing code; shown on the phone so it can be read aloud. */
    fun familyUrl(): String = if (baseUrl.isBlank()) "" else "$baseUrl/"

    /** The one-time pairing: the phone asks for a token and a code the family will type in. */
    suspend fun pair(): String = withContext(Dispatchers.IO) {
        val body = JSONObject().put("elder_name", elderName).toString()
        val json = request("POST", "/api/device/pair", body, token = null)
            ?: throw ServerError("配对失败：服务器没有回应。")
        token = json.optString("token")
        pairCode = json.optString("pair_code")
        heartbeatSeconds = json.optInt("heartbeat_seconds", 300)
        lastResult = "已配对（配对码 ${pairCode}）"
        LoopLog.event("[server] 配对成功 code=$pairCode")
        pairCode
    }

    /** Proof of life, and the inbox: whoever is running is also who gets told things. */
    suspend fun heartbeat(note: String): List<PendingMessage> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext emptyList()
        val body = JSONObject().put("note", note).toString()
        val json = request("POST", "/api/device/heartbeat", body, token) ?: return@withContext emptyList()
        lastHeartbeatAt = System.currentTimeMillis()
        lastResult = "刚刚联系过服务器"
        val pending = json.optJSONArray("pending") ?: return@withContext emptyList()
        (0 until pending.length()).map { index ->
            val item = pending.getJSONObject(index)
            PendingMessage(
                id = item.optInt("id"),
                kind = item.optString("kind"),
                title = item.optString("title"),
                body = item.optString("body"),
                from = item.optString("from"),
            )
        }
    }

    suspend fun postEvent(kind: String, title: String, body: String, context: String = ""): Boolean =
        withContext(Dispatchers.IO) {
            if (!isConfigured()) return@withContext false
            val payload = JSONObject()
                .put("kind", kind)
                .put("title", title)
                .put("body", body)
                .put("context", context)
                .toString()
            val json = request("POST", "/api/device/events", payload, token)
            val ok = json != null
            lastResult = if (ok) "已上报：$title" else "上报失败"
            LoopLog.event("[server] 上报 $kind ok=$ok")
            ok
        }

    private fun request(method: String, path: String, body: String?, token: String?): JSONObject? {
        val url = try {
            URL(baseUrl + path)
        } catch (_: Exception) {
            lastResult = "服务器地址无法识别"
            return null
        }
        if (url.protocol != "https" && url.host !in setOf("127.0.0.1", "localhost", "::1")) {
            lastResult = "服务器地址必须是 HTTPS"
            LoopLog.event("[server] 拒绝明文地址 ${url.host}")
            return null
        }
        var connection: HttpURLConnection? = null
        return try {
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 8_000
                readTimeout = 12_000
                doOutput = body != null
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
            }
            if (body != null) {
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                lastResult = "服务器返回 $code"
                LoopLog.event("[server] $method $path → $code ${text.take(120)}")
                return null
            }
            if (text.isBlank()) JSONObject() else JSONObject(text)
        } catch (io: IOException) {
            lastResult = "联系不上服务器（${io.javaClass.simpleName}）"
            LoopLog.event("[server] $method $path 失败：${io.message}")
            null
        } catch (error: Exception) {
            lastResult = "服务器响应无法解析"
            LoopLog.event("[server] $method $path 解析失败：${error.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }
}
