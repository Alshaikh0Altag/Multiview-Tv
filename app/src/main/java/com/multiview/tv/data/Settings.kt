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
        // السعودية والعرب
        "ksa.1" to "دوري روشن السعودي",
        "ksa.kings.cup" to "كأس خادم الحرمين الشريفين",
        "egy.1" to "الدوري المصري",
        "uae.1" to "الدوري الإماراتي",
        "qat.1" to "الدوري القطري",
        "mar.1" to "الدوري المغربي",
        "tun.1" to "الدوري التونسي",
        "alg.1" to "الدوري الجزائري",
        // آسيا وأفريقيا
        "afc.champions" to "دوري أبطال آسيا للنخبة",
        "afc.cup" to "دوري أبطال آسيا 2",
        "afc.asian.cup" to "كأس آسيا",
        "caf.champions" to "دوري أبطال أفريقيا",
        "caf.nations" to "كأس أمم أفريقيا",
        "caf.nations_qual" to "تصفيات كأس أمم أفريقيا",
        // أوروبا — بطولات
        "uefa.champions" to "دوري أبطال أوروبا",
        "uefa.europa" to "الدوري الأوروبي",
        "uefa.europa.conf" to "دوري المؤتمر الأوروبي",
        "uefa.super_cup" to "السوبر الأوروبي",
        "uefa.nations" to "دوري الأمم الأوروبية",
        "uefa.euro" to "كأس أمم أوروبا",
        // أوروبا — دوريات وكؤوس
        "eng.1" to "الدوري الإنجليزي",
        "eng.2" to "دوري الدرجة الأولى الإنجليزي",
        "eng.fa" to "كأس الاتحاد الإنجليزي",
        "eng.league_cup" to "كأس الرابطة الإنجليزية",
        "esp.1" to "الدوري الإسباني",
        "esp.copa_del_rey" to "كأس ملك إسبانيا",
        "esp.super_cup" to "السوبر الإسباني",
        "ita.1" to "الدوري الإيطالي",
        "ita.coppa_italia" to "كأس إيطاليا",
        "ger.1" to "الدوري الألماني",
        "ger.dfb_pokal" to "كأس ألمانيا",
        "fra.1" to "الدوري الفرنسي",
        "fra.coupe_de_france" to "كأس فرنسا",
        "ned.1" to "الدوري الهولندي",
        "por.1" to "الدوري البرتغالي",
        "tur.1" to "الدوري التركي",
        "sco.1" to "الدوري الاسكتلندي",
        "bel.1" to "الدوري البلجيكي",
        // العالم
        "fifa.world" to "كأس العالم",
        "fifa.cwc" to "كأس العالم للأندية",
        "fifa.worldq.afc" to "تصفيات كأس العالم — آسيا",
        "fifa.worldq.caf" to "تصفيات كأس العالم — أفريقيا",
        "fifa.worldq.uefa" to "تصفيات كأس العالم — أوروبا",
        "fifa.worldq.conmebol" to "تصفيات كأس العالم — أمريكا الجنوبية",
        "fifa.friendly" to "مباريات ودية دولية",
        "club.friendly" to "مباريات ودية للأندية",
        "conmebol.libertadores" to "كوبا ليبرتادوريس",
        "conmebol.america" to "كوبا أمريكا",
        "usa.1" to "الدوري الأمريكي MLS",
        "bra.1" to "الدوري البرازيلي",
        "arg.1" to "الدوري الأرجنتيني",
    )
    /** Leagues added in an update, switched on once for everyone (even if the list was customised). */
    private val NEW_DEFAULT_LEAGUES = listOf("caf.nations_qual")

    fun leagues(c: Context): Set<String> {
        val p = sp(c)
        val set = leaguesRaw(c).toMutableSet()
        val pending = NEW_DEFAULT_LEAGUES.filter { !p.getBoolean("lg_added_$it", false) }
        if (pending.isNotEmpty()) {
            set += pending
            val e = p.edit().putStringSet("leagues", set)
            pending.forEach { e.putBoolean("lg_added_$it", true) }
            e.apply()
        }
        return set
    }

    private fun leaguesRaw(c: Context): Set<String> =
        sp(c).getStringSet("leagues", setOf(
            "ksa.1", "ksa.kings.cup", "egy.1", "afc.champions", "uefa.champions", "uefa.europa",
            "eng.1", "esp.1", "ita.1", "ger.1", "fra.1", "fifa.world", "fifa.cwc", "fifa.worldq.afc", "caf.nations_qual",
        ))!!
    fun setLeagues(c: Context, v: Set<String>) = sp(c).edit().putStringSet("leagues", v).apply()

    /**
     * Movie / series categories pinned to the home screen (long-press a category).
     * Each entry: "m:<categoryId>" or "s:<categoryId>" → display name. Insertion order kept.
     */
    fun pinnedVod(c: Context): LinkedHashMap<String, String> {
        val o = org.json.JSONObject(sp(c).getString("pinned_vod", "{}")!!)
        val out = LinkedHashMap<String, String>()
        o.keys().forEach { out[it] = o.getString(it) }
        return out
    }
    fun setPinnedVod(c: Context, v: Map<String, String>) {
        val o = org.json.JSONObject()
        v.forEach { (k, n) -> o.put(k, n) }
        sp(c).edit().putString("pinned_vod", o.toString()).apply()
    }

    /** Today's matches: "all" | "live" | "upcoming", and "time" | "importance" */
    fun matchesMode(c: Context) = sp(c).getString("matches_mode", "all")!!
    fun setMatchesMode(c: Context, v: String) = sp(c).edit().putString("matches_mode", v).apply()
    fun matchesSort(c: Context) = sp(c).getString("matches_sort", "importance")!!
    fun setMatchesSort(c: Context, v: String) = sp(c).edit().putString("matches_sort", v).apply()

    /** Movies & series order: "recent" (default) / "rating" / "name" */
    fun vodSort(c: Context) = sp(c).getString("vod_sort", "recent")!!
    fun setVodSort(c: Context, v: String) = sp(c).edit().putString("vod_sort", v).apply()

    fun tennisEnabled(c: Context) = sp(c).getBoolean("tennis", true)
    fun setTennisEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("tennis", v).apply()

    /** Movies / series order: "new" (default), "rating", "name" */
    fun vodSort(c: Context) = sp(c).getString("vod_sort", "new")!!
    fun setVodSort(c: Context, v: String) = sp(c).edit().putString("vod_sort", v).apply()

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
