package com.multiview.tv.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updates from the GitHub releases of the project
 * (each build publishes MultiViewTV.apk under a "build-N" tag; N = versionCode).
 * The repository must be public for the TV to read its releases.
 */
object Updater {
    const val REPO = "Alshaikh0Altag/Multiview-Tv"

    data class Release(val versionCode: Int, val name: String, val apkUrl: String)

    fun currentVersionCode(c: Context): Int {
        val info = c.packageManager.getPackageInfo(c.packageName, 0)
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
    }

    fun currentVersionName(c: Context): String =
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: ""

    /** Latest release, or null when it can't be read (private repo, no internet…). */
    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val json = JSONObject(Http.get("https://api.github.com/repos/$REPO/releases/latest", timeoutMs = 15_000))
            val tag = json.optString("tag_name")
            val code = tag.substringAfterLast('-').toIntOrNull() ?: return@runCatching null
            val assets = json.optJSONArray("assets") ?: return@runCatching null
            var url: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) { url = a.optString("browser_download_url"); break }
            }
            url?.let { Release(code, "1.$code", it) }
        }.getOrNull()
    }

    suspend fun download(c: Context, url: String, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(c.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "update.apk")
        var target = url
        var conn: HttpURLConnection
        var hops = 0
        // follow redirects (GitHub → its download host) by hand, across hosts
        while (true) {
            conn = URL(target).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", Http.DEFAULT_USER_AGENT)
            val code = conn.responseCode
            if (code in 300..399 && hops < 5) {
                target = conn.getHeaderField("Location") ?: break
                conn.disconnect(); hops++; continue
            }
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            break
        }
        val total = conn.contentLength.toLong()
        conn.inputStream.use { input ->
            out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var last = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val p = (done * 100 / total).toInt()
                        if (p != last) { last = p; withContext(Dispatchers.Main) { onProgress(p) } }
                    }
                }
            }
        }
        conn.disconnect()
        out
    }

    /** True when the system allows this app to install APKs (Android 8+ asks once). */
    fun canInstall(c: Context) = Build.VERSION.SDK_INT < 26 || c.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(c: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            c.startActivity(Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun install(c: Context, apk: File) {
        val uri = FileProvider.getUriForFile(c, "${c.packageName}.files", apk)
        c.startActivity(Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
