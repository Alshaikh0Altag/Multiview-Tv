package com.multiview.tv.data

import android.content.Context
import android.content.SharedPreferences

/** User settings shown in the Settings screen. */
object Settings {
    private const val FILE = "multiview_settings"
    private fun sp(c: Context): SharedPreferences = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // Clock
    fun clockEnabled(c: Context) = sp(c).getBoolean("clock", true)
    fun setClockEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("clock", v).apply()
    /** "right" or "left" (top corner, absolute — not affected by RTL) */
    fun clockPosition(c: Context) = sp(c).getString("clock_pos", "right")!!
    fun setClockPosition(c: Context, v: String) = sp(c).edit().putString("clock_pos", v).apply()
    fun clock24h(c: Context) = sp(c).getBoolean("clock_24", true)
    fun setClock24h(c: Context, v: Boolean) = sp(c).edit().putBoolean("clock_24", v).apply()

    // Playback
    /** "hw" or "sw" */
    fun decoder(c: Context) = sp(c).getString("decoder", "hw")!!
    fun setDecoder(c: Context, v: String) = sp(c).edit().putString("decoder", v).apply()
    fun autoFrameRate(c: Context) = sp(c).getBoolean("afr", false)
    fun setAutoFrameRate(c: Context, v: Boolean) = sp(c).edit().putBoolean("afr", v).apply()
    /** max video height: 0 = no limit, else 1080 / 720 / 480 */
    fun maxHeight(c: Context) = sp(c).getInt("max_h", 0)
    fun setMaxHeight(c: Context, v: Int) = sp(c).edit().putInt("max_h", v).apply()
    /** AspectRatioFrameLayout.RESIZE_MODE_* */
    fun resizeMode(c: Context) = sp(c).getInt("resize", 0)
    fun setResizeMode(c: Context, v: Int) = sp(c).edit().putInt("resize", v).apply()

    /** Xtream live format: "ts" or "m3u8" (HLS) */
    fun liveFormat(c: Context) = sp(c).getString("live_fmt", "ts")!!
    fun setLiveFormat(c: Context, v: String) = sp(c).edit().putString("live_fmt", v).apply()

    // Startup
    fun autoplayLast(c: Context) = sp(c).getBoolean("autoplay", false)
    fun setAutoplayLast(c: Context, v: Boolean) = sp(c).edit().putBoolean("autoplay", v).apply()
    fun lastChannel(c: Context) = sp(c).getString("last_ch", null)
    fun setLastChannel(c: Context, id: String) = sp(c).edit().putString("last_ch", id).apply()

    // Groups (keys are prefixed: "l:" live, "m:" movies, "s:" series)
    fun hiddenGroups(c: Context): Set<String> = sp(c).getStringSet("hidden", emptySet())!!
    fun setHiddenGroups(c: Context, v: Set<String>) = sp(c).edit().putStringSet("hidden", v).apply()
    fun lockedGroups(c: Context): Set<String> = sp(c).getStringSet("locked", emptySet())!!
    fun setLockedGroups(c: Context, v: Set<String>) = sp(c).edit().putStringSet("locked", v).apply()

    // Parental PIN (default 0000)
    fun pin(c: Context) = sp(c).getString("pin", "0000")!!
    fun setPin(c: Context, v: String) = sp(c).edit().putString("pin", v).apply()
}
