package com.multiview.tv

import android.app.Application
import android.content.Context
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Keeps the last crash so the next start can show it (a photo of it is enough to fix the bug).
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val info = packageManager.getPackageInfo(packageName, 0)
                getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                    .putString(KEY, "v${info.versionName} · ${t.name}\n" + sw.toString().take(4000))
                    .commit()
            }
            previous?.uncaughtException(t, e)
        }
    }

    companion object {
        /** Application context for helpers that have no screen of their own. */
        lateinit var instance: App
            private set

        private const val FILE = "crash"
        private const val KEY = "last"

        /** Returns the last crash report once, then forgets it. */
        fun takeLastCrash(c: Context): String? {
            val sp = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            val r = sp.getString(KEY, null) ?: return null
            sp.edit().remove(KEY).apply()
            return r
        }
    }
}
