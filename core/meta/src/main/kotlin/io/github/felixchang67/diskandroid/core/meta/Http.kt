package io.github.felixchang67.diskandroid.core.meta

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class HttpResponse(val code: Int, val body: ByteArray) {
    val text: String get() = body.toString(Charsets.UTF_8)
}

/** 联网查询都走这个接口（同步调用，调用方放在 IO 线程上）；测试里换成返回固定数据的假实现。 */
interface HttpClient {
    fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse

    fun post(url: String, form: Map<String, String>, headers: Map<String, String> = emptyMap()): HttpResponse
}

class MetaException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** java.net.HttpURLConnection 实现（Android 与 JVM 都可用，不引入额外依赖）。 */
class UrlConnectionHttpClient(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 15_000,
    private val maxBytes: Int = 8 * 1024 * 1024,
) : HttpClient {
    override fun get(url: String, headers: Map<String, String>): HttpResponse = request(url, "GET", null, headers)

    override fun post(url: String, form: Map<String, String>, headers: Map<String, String>): HttpResponse {
        val body = form.entries.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }.toByteArray(Charsets.UTF_8)
        return request(url, "POST", body, headers + ("Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"))
    }

    private fun request(url: String, method: String, body: ByteArray?, headers: Map<String, String>): HttpResponse {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.instanceFollowRedirects = true
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val bytes = stream?.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) throw MetaException("响应超过 ${maxBytes / 1024 / 1024} MB")
                }
                out.toByteArray()
            } ?: ByteArray(0)
            return HttpResponse(code, bytes)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    }
}

// ---------------- JSON 树的小工具（只用 kotlinx.serialization 的树 API） ----------------

internal fun parseJsonObject(text: String): JsonObject =
    runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: throw MetaException("不是 JSON 对象：" + text.take(120))

internal fun JsonElement?.asObj(): JsonObject? = this as? JsonObject

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()

internal fun JsonObject.str(key: String): String? {
    val p = this[key] as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.content
}

internal fun JsonObject.long(key: String): Long? {
    val p = this[key] as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.longOrNull ?: p.doubleOrNull?.toLong() ?: p.content.toLongOrNull()
}

internal fun JsonObject.int(key: String): Int? = long(key)?.toInt()

internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.booleanOrNull
