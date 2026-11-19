package dev.freeapi.explorer.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class HttpException(val code: Int, message: String) : Exception(message)

/**
 * Polite HTTP for free APIs: a disk cache (TTL per call), one request at a time per host with a minimum gap
 * for hosts that ask for one, and stale-on-error (a flaky free server shows the last good answer).
 */
class Http(cacheRoot: File) {
    private val dir = File(cacheRoot, "http").apply { mkdirs() }
    private val minGapMs = mapOf(
        "nominatim.openstreetmap.org" to 1_100L, // their policy: max 1 req/s
        "overpass-api.de" to 1_000L,
        "overpass.private.coffee" to 1_000L,
    )
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val lastCall = ConcurrentHashMap<String, Long>()

    suspend fun text(url: String, ttlMs: Long, headers: Map<String, String> = emptyMap()): String =
        String(bytes(url, ttlMs, headers), Charsets.UTF_8)

    suspend fun bytes(url: String, ttlMs: Long, headers: Map<String, String> = emptyMap()): ByteArray =
        withContext(Dispatchers.IO) {
            val file = File(dir, sha1(url))
            val now = System.currentTimeMillis()
            if (ttlMs > 0 && file.exists() && now - file.lastModified() < ttlMs) return@withContext file.readBytes()
            val host = URL(url).host
            try {
                val body = locks.getOrPut(host) { Mutex() }.withLock {
                    val wait = (lastCall[host] ?: 0L) + (minGapMs[host] ?: 0L) - System.currentTimeMillis()
                    if (wait > 0) delay(wait)
                    try { fetch(url, headers) } finally { lastCall[host] = System.currentTimeMillis() }
                }
                if (ttlMs > 0) runCatching { file.writeBytes(body) }
                body
            } catch (e: Exception) {
                if (file.exists()) file.readBytes() else throw e
            }
        }

    private fun fetch(url: String, headers: Map<String, String>): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json, application/geo+json, */*")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { it.readBytes() } ?: ByteArray(0)
            if (code !in 200..299) {
                val snippet = String(body, Charsets.UTF_8).replace(Regex("<[^>]+>"), " ").trim().take(140)
                throw HttpException(code, "HTTP $code from ${URL(url).host}: $snippet")
            }
            return body
        } finally {
            conn.disconnect()
        }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        const val USER_AGENT = "FreeApiExplorer/0.1 (Android; personal use)"
    }
}
