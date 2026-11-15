package com.multiview.tv.data

import android.util.JsonReader
import android.util.JsonToken
import java.net.HttpURLConnection
import java.net.URL

object Http {
    const val DEFAULT_USER_AGENT = "MultiViewTV/1.0"

    /** Set from Settings at start-up; used for API calls and for the video streams. */
    @Volatile var userAgent: String = DEFAULT_USER_AGENT

    private fun open(url: String, timeoutMs: Int): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs * 3
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", userAgent)
        val code = conn.responseCode
        if (code !in 200..299) { conn.disconnect(); throw IllegalStateException("HTTP $code") }
        return conn
    }

    fun get(url: String, timeoutMs: Int = 20_000): String {
        val conn = open(url, timeoutMs)
        try {
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Streams a big JSON array of flat objects without holding the whole text in memory.
     * Each object is handed to [map] as field → string (nested values are skipped).
     */
    fun <T> streamArray(url: String, timeoutMs: Int = 60_000, map: (Map<String, String>) -> T?): List<T> {
        val conn = open(url, timeoutMs)
        val out = ArrayList<T>()
        try {
            JsonReader(conn.inputStream.bufferedReader(Charsets.UTF_8)).use { r ->
                if (r.peek() != JsonToken.BEGIN_ARRAY) { r.skipValue(); return out }
                r.beginArray()
                val fields = HashMap<String, String>(24)
                while (r.hasNext()) {
                    if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
                    fields.clear()
                    r.beginObject()
                    while (r.hasNext()) {
                        val name = r.nextName()
                        when (r.peek()) {
                            JsonToken.STRING, JsonToken.NUMBER -> fields[name] = r.nextString()
                            JsonToken.BOOLEAN -> fields[name] = r.nextBoolean().toString()
                            JsonToken.NULL -> r.nextNull()
                            else -> r.skipValue()
                        }
                    }
                    r.endObject()
                    map(fields)?.let { out += it }
                }
                r.endArray()
            }
        } finally {
            conn.disconnect()
        }
        return out
    }
}
