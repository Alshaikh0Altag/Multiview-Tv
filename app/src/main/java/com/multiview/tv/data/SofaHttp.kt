package com.multiview.tv.data

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import com.multiview.tv.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

/**
 * Reads Sofascore's public JSON (the same data its website uses).
 * Tries the API hosts directly; if they refuse (403 for non-browser clients),
 * loads the address in an invisible WebView — a real browser engine — and reads the JSON text.
 */
object SofaHttp {
    const val UA = "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    private val hosts = listOf("https://api.sofascore.com", "https://api.sofascore.app", "https://www.sofascore.com")

    /** Once the direct route is refused, go straight to the WebView for the rest of the session. */
    @Volatile private var directBlocked = false

    /** @param path e.g. "/api/v1/event/123" */
    suspend fun get(path: String): String {
        if (!directBlocked) {
            var refused = 0
            for (h in hosts) {
                val r = withContext(Dispatchers.IO) { runCatching { direct(h + path) } }
                r.getOrNull()?.let { return it }
                val code = (r.exceptionOrNull() as? HttpError)?.code
                if (code == 404) throw HttpError(404)        // no such data: don't retry elsewhere
                if (code == 403 || code == 429) refused++
            }
            if (refused == hosts.size) directBlocked = true
        }
        return viaWebView("https://www.sofascore.com$path") ?: throw IllegalStateException("Sofascore unavailable")
    }

    class HttpError(val code: Int) : Exception("HTTP $code")

    private fun direct(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept", "application/json, text/plain, */*")
        conn.setRequestProperty("Accept-Language", "ar,en;q=0.8")
        conn.setRequestProperty("Origin", "https://www.sofascore.com")
        conn.setRequestProperty("Referer", "https://www.sofascore.com/")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw HttpError(code)
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (!body.trimStart().startsWith("{") && !body.trimStart().startsWith("[")) throw HttpError(403)
            return body
        } finally {
            conn.disconnect()
        }
    }

    // ---------- WebView fallback (one at a time) ----------

    private val main = Handler(Looper.getMainLooper())
    private val lock = kotlinx.coroutines.sync.Mutex()
    @SuppressLint("StaticFieldLeak") private var web: WebView? = null

    private suspend fun viaWebView(url: String): String? = lock.withLock {
        withTimeoutOrNull(20_000) {
            suspendCancellableCoroutine { cont ->
                main.post {
                    val w = web ?: WebView(App.instance).also { v ->
                        @SuppressLint("SetJavaScriptEnabled")
                        v.settings.javaScriptEnabled = true
                        v.settings.userAgentString = UA
                        web = v
                    }
                    var done = false
                    fun poll(tries: Int) {
                        if (done || !cont.isActive) return
                        w.evaluateJavascript("(function(){return document.body?document.body.innerText:''})()") { raw ->
                            val all = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull().orEmpty()
                            // the browser may print a small toolbar before the JSON — start at the first brace
                            val start = all.indexOfFirst { it == '{' || it == '[' }
                            val text = if (start >= 0) all.substring(start).trim() else ""
                            if (text.isNotEmpty() && runCatching { JSONTokener(text).nextValue() }.isSuccess) {
                                done = true
                                if (cont.isActive) cont.resume(text)
                            } else if (tries > 0) {
                                main.postDelayed({ poll(tries - 1) }, 700)   // e.g. a browser check page, then the JSON
                            } else if (cont.isActive) {
                                done = true; cont.resume(null)
                            }
                        }
                    }
                    w.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, u: String?) { poll(20) }
                    }
                    w.loadUrl(url)
                }
            }
        }
    }
}
