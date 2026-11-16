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

    /** Clock look: "box" (dark background) or "plain" (text only, with a soft shadow) */
    fun clockStyle(c: Context) = sp(c).getString("clock_style", "box")!!
    fun setClockStyle(c: Context, v: String) = sp(c).edit().putString("clock_style", v).apply()
    /** Clock text size in sp: 16 / 20 / 26 */
    fun clockSize(c: Context) = sp(c).getInt("clock_size", 20)
    fun setClockSize(c: Context, v: Int) = sp(c).edit().putInt("clock_size", v).apply()

    /** false: ▲ = next channel (default). true: ▲ = previous channel. */
    fun reverseArrows(c: Context) = sp(c).getBoolean("rev_arrows", false)
    fun setReverseArrows(c: Context, v: Boolean) = sp(c).edit().putBoolean("rev_arrows", v).apply()

    /** clock opacity in percent: 100 / 75 / 50 / 25 */
    fun clockOpacity(c: Context) = sp(c).getInt("clock_alpha", 100)
    fun setClockOpacity(c: Context, v: Int) = sp(c).edit().putInt("clock_alpha", v).apply()

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

    /** "small" / "normal" / "large" — bigger buffer = fewer pauses, slower start */
    fun bufferSize(c: Context) = sp(c).getString("buffer", "normal")!!
    fun setBufferSize(c: Context, v: String) = sp(c).edit().putString("buffer", v).apply()

    /** Custom HTTP User-Agent (some providers require a specific one). Empty = default. */
    fun userAgent(c: Context) = sp(c).getString("ua", "")!!
    fun setUserAgent(c: Context, v: String) = sp(c).edit().putString("ua", v).apply()

    /** Subtitle size in percent: 80 / 100 / 130 / 160 */
    fun subtitleSize(c: Context) = sp(c).getInt("sub_size", 100)
    fun setSubtitleSize(c: Context, v: Int) = sp(c).edit().putInt("sub_size", v).apply()

    /** Channel order: "number" or "name" */
    fun channelSort(c: Context) = sp(c).getString("sort", "number")!!
    fun setChannelSort(c: Context, v: String) = sp(c).edit().putString("sort", v).apply()

    /** Recently watched channel ids, newest first. */
    fun recentChannels(c: Context): List<String> =
        sp(c).getString("recent_ch", "")!!.split(',').filter { it.isNotBlank() }

    fun pushRecentChannel(c: Context, id: String) {
        val list = (listOf(id) + recentChannels(c).filter { it != id }).take(30)
        sp(c).edit().putString("recent_ch", list.joinToString(",")).apply()
    }

    // ---------- Home screen ----------
    /** Rows shown on the home screen, in order. */
    val ALL_HOME_ROWS = listOf("matches", "continue", "recent", "favorites", "lists", "fav_movies", "fav_series")
    fun homeRows(c: Context): List<String> =
        sp(c).getString("home_rows", "matches,continue,recent,favorites,lists")!!.split(',').filter { it in ALL_HOME_ROWS }
    fun setHomeRows(c: Context, v: List<String>) = sp(c).edit().putString("home_rows", v.joinToString(",")).apply()

    /** ESPN league codes shown in "today's matches". */
    val ALL_LEAGUES = linkedMapOf(
        "ksa.1" to "دوري روشن السعودي",
        "afc.champions" to "دوري أبطال آسيا",
        "uefa.champions" to "دوري أبطال أوروبا",
        "eng.1" to "الدوري الإنجليزي",
        "esp.1" to "الدوري الإسباني",
        "ita.1" to "الدوري الإيطالي",
        "ger.1" to "الدوري الألماني",
        "fra.1" to "الدوري الفرنسي",
        "uefa.europa" to "الدوري الأوروبي",
        "fifa.world" to "كأس العالم",
        "fifa.friendly" to "مباريات ودية دولية",
    )
    fun leagues(c: Context): Set<String> =
        sp(c).getStringSet("leagues", setOf("ksa.1", "afc.champions", "uefa.champions", "eng.1", "esp.1", "ita.1", "fifa.world"))!!
    fun setLeagues(c: Context, v: Set<String>) = sp(c).edit().putStringSet("leagues", v).apply()

    // ---------- Movies & series playback ----------
    /** Seconds moved by ◀ ▶ when the controls are hidden */
    fun seekStep(c: Context) = sp(c).getInt("seek_step", 10)
    fun setSeekStep(c: Context, v: Int) = sp(c).edit().putInt("seek_step", v).apply()
    /** Seconds jumped by "skip intro" */
    fun introSkip(c: Context) = sp(c).getInt("intro_skip", 90)
    fun setIntroSkip(c: Context, v: Int) = sp(c).edit().putInt("intro_skip", v).apply()
    fun autoNext(c: Context) = sp(c).getBoolean("auto_next", true)
    fun setAutoNext(c: Context, v: Boolean) = sp(c).edit().putBoolean("auto_next", v).apply()

    // ---------- Channels: hidden / locked / custom lists ----------
    fun hiddenChannels(c: Context): Set<String> = sp(c).getStringSet("hidden_ch", emptySet())!!
    fun setHiddenChannels(c: Context, v: Set<String>) = sp(c).edit().putStringSet("hidden_ch", v).apply()
    fun lockedChannels(c: Context): Set<String> = sp(c).getStringSet("locked_ch", emptySet())!!
    fun setLockedChannels(c: Context, v: Set<String>) = sp(c).edit().putStringSet("locked_ch", v).apply()

    /** Custom channel lists: name → channel ids (in insertion order). */
    fun customLists(c: Context): LinkedHashMap<String, List<String>> {
        val o = org.json.JSONObject(sp(c).getString("custom_lists", "{}")!!)
        val out = LinkedHashMap<String, List<String>>()
        o.keys().forEach { k ->
            val a = o.getJSONArray(k)
            out[k] = (0 until a.length()).map { a.getString(it) }
        }
        return out
    }
    fun setCustomLists(c: Context, v: Map<String, List<String>>) {
        val o = org.json.JSONObject()
        v.forEach { (k, ids) -> o.put(k, org.json.JSONArray(ids)) }
        sp(c).edit().putString("custom_lists", o.toString()).apply()
    }

    // ---------- Updates ----------
    fun lastUpdateCheck(c: Context) = sp(c).getLong("upd_check", 0)
    fun setLastUpdateCheck(c: Context, v: Long) = sp(c).edit().putLong("upd_check", v).apply()

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
