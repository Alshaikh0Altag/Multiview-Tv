package com.multiview.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** In-memory store shared by all screens. */
object Repository {
    const val FAVORITES_ID = "__fav__"
    const val ALL_ID = "__all__"
    const val RECENT_ID = "__recent__"

    /** Set from Settings: recently watched channel ids (newest first) and the channel order. */
    var recentIds: List<String> = emptyList()
    var sortByName: Boolean = false
    var hiddenChannels: Set<String> = emptySet()
    var customLists: Map<String, List<String>> = emptyMap()
    const val LIST_PREFIX = "__list__:"

    var playlist: Playlist? = null
        private set
    var account: AccountInfo? = null
        private set

    // live
    var categories: List<Category> = emptyList()
        private set
    var channels: List<Channel> = emptyList()
        private set

    // movies & series: categories first, then each category on demand (cached)
    var vodCategories: List<Category> = emptyList()
        private set
    var seriesCategories: List<Category> = emptyList()
        private set
    private val moviesByCat = HashMap<String, List<Movie>>()
    private val seriesByCat = HashMap<String, List<Series>>()
    private val seriesById = HashMap<String, Series>()
    private var cachedMovies: List<Movie>? = null
    private var cachedSeries: List<Series>? = null
    private val movieInfoCache = HashMap<String, MovieDetails>()

    private var xtream: XtreamApi? = null
    private val seriesCache = HashMap<String, SeriesDetails>()

    /** Four multiview slots; null = empty. */
    val multiView: Array<Channel?> = arrayOfNulls(4)

    val supportsVod: Boolean get() = xtream != null

    suspend fun load(p: Playlist, liveExt: String = "ts"): Unit = withContext(Dispatchers.IO) {
        playlist = p
        vodCategories = emptyList(); seriesCategories = emptyList()
        moviesByCat.clear(); seriesByCat.clear(); seriesById.clear()
        cachedMovies = null; cachedSeries = null; movieInfoCache.clear()
        seriesCache.clear()
        if (p.type == "xtream") {
            val api = XtreamApi(p.server, p.username, p.password, liveExt)
            val info = api.accountInfo()
            if (!info.active) throw IllegalStateException("الاشتراك غير فعّال أو بيانات الدخول خاطئة")
            account = info
            xtream = api
            categories = api.liveCategories()
            channels = api.liveStreams().sortedBy { it.number }
        } else {
            val r = M3uParser.parse(Http.get(p.m3uUrl, timeoutMs = 30_000))
            if (r.channels.isEmpty()) throw IllegalStateException("لم يتم العثور على قنوات في الرابط")
            account = null
            xtream = null
            categories = r.categories
            channels = r.channels
        }
        Unit
    }

    suspend fun loadVodCategories(): Unit = withContext(Dispatchers.IO) {
        val api = xtream ?: return@withContext
        if (vodCategories.isEmpty()) vodCategories = api.vodCategories()
    }

    suspend fun loadSeriesCategories(): Unit = withContext(Dispatchers.IO) {
        val api = xtream ?: return@withContext
        if (seriesCategories.isEmpty()) seriesCategories = api.seriesCategories()
    }

    suspend fun moviesIn(categoryId: String): List<Movie> = withContext(Dispatchers.IO) {
        moviesByCat[categoryId] ?: xtream!!.vodStreams(categoryId).also { moviesByCat[categoryId] = it }
    }

    suspend fun seriesIn(categoryId: String): List<Series> = withContext(Dispatchers.IO) {
        seriesByCat[categoryId] ?: xtream!!.seriesList(categoryId).also { list ->
            seriesByCat[categoryId] = list
            list.forEach { seriesById[it.id] = it }
        }
    }

    fun seriesById(id: String?): Series? = id?.let { seriesById[it] }

    fun registerSeries(list: Collection<Series>) = list.forEach { seriesById[it.id] = it }

    /** Whole movie library (streamed once, then cached). */
    suspend fun allMovies(): List<Movie> = withContext(Dispatchers.IO) {
        cachedMovies ?: xtream!!.vodStreams(null).also { cachedMovies = it }
    }

    suspend fun allSeries(): List<Series> = withContext(Dispatchers.IO) {
        cachedSeries ?: xtream!!.seriesList(null).also { list -> cachedSeries = list; registerSeries(list) }
    }

    suspend fun recentMovies(limit: Int = 150) = allMovies().sortedByDescending { it.added }.take(limit)

    suspend fun recentSeries(limit: Int = 150) = allSeries().sortedByDescending { it.added }.take(limit)

    suspend fun movieInfo(id: String): MovieDetails = withContext(Dispatchers.IO) {
        movieInfoCache[id] ?: xtream!!.vodInfo(id).also { movieInfoCache[id] = it }
    }

    suspend fun seriesDetails(s: Series): SeriesDetails = withContext(Dispatchers.IO) {
        seriesCache[s.id] ?: xtream!!.seriesInfo(s).also { seriesCache[s.id] = it }
    }

    /** @param excluded raw category ids hidden or still locked — left out of "all channels" */
    fun channelsIn(categoryId: String, favorites: Set<String>, excluded: Set<String> = emptySet()): List<Channel> {
        if (categoryId == RECENT_ID || categoryId.startsWith(LIST_PREFIX)) {
            val ids = if (categoryId == RECENT_ID) recentIds else customLists[categoryId.removePrefix(LIST_PREFIX)].orEmpty()
            val byId = channels.associateBy { it.id }
            return ids.mapNotNull { byId[it] }.filter { it.categoryId !in excluded && it.id !in hiddenChannels }
        }
        val all = when (categoryId) {
            ALL_ID -> if (excluded.isEmpty()) channels else channels.filter { it.categoryId !in excluded }
            FAVORITES_ID -> channels.filter { favorites.contains(it.id) }
            else -> channels.filter { it.categoryId == categoryId }
        }
        val list = if (hiddenChannels.isEmpty()) all else all.filter { it.id !in hiddenChannels }
        return if (sortByName) list.sortedBy { it.name.lowercase() } else list
    }

    fun addToMultiView(channel: Channel): Int {
        val idx = multiView.indexOfFirst { it == null }
        if (idx >= 0) multiView[idx] = channel
        return idx
    }

    fun maxConnections(): Int? = account?.maxConnections

    // ---------- instant start: last channel list kept on the device ----------

    private const val CACHE = "channels_cache.json"
    private fun cacheKey(p: Playlist, liveExt: String) = listOf(p.type, p.server, p.username, p.m3uUrl, liveExt).joinToString("|")

    /** Saves the loaded channel list so the next start can open Home immediately. */
    fun persist(c: android.content.Context, liveExt: String) {
        val p = playlist ?: return
        runCatching {
            val o = org.json.JSONObject().put("key", cacheKey(p, liveExt))
            o.put("cats", org.json.JSONArray().apply { categories.forEach { put(org.json.JSONObject().put("i", it.id).put("n", it.name)) } })
            o.put("ch", org.json.JSONArray().apply {
                channels.forEach {
                    put(org.json.JSONObject().put("i", it.id).put("n", it.number).put("t", it.name)
                        .put("l", it.logo ?: "").put("c", it.categoryId).put("u", it.url))
                }
            })
            account?.let { a ->
                o.put("acc", org.json.JSONObject().put("active", a.active).put("max", a.maxConnections)
                    .put("exp", a.expiresAt ?: 0).put("cons", a.activeConnections).put("created", a.createdAt ?: 0)
                    .put("status", a.status).put("user", a.username))
            }
            java.io.File(c.filesDir, CACHE).writeText(o.toString())
        }
    }

    /** Loads the saved list (no network). @return false when there is none for this playlist. */
    fun restore(c: android.content.Context, p: Playlist, liveExt: String): Boolean {
        val f = java.io.File(c.filesDir, CACHE)
        if (!f.exists()) return false
        return runCatching { restoreFrom(f, p, liveExt) }.getOrDefault(false)
    }

    private fun restoreFrom(f: java.io.File, p: Playlist, liveExt: String): Boolean {
        val o = org.json.JSONObject(f.readText())
        if (o.optString("key") != cacheKey(p, liveExt)) return false
        val cats = o.getJSONArray("cats")
        val ch = o.getJSONArray("ch")
        categories = (0 until cats.length()).map { val x = cats.getJSONObject(it); Category(x.getString("i"), x.getString("n")) }
        channels = (0 until ch.length()).map {
            val x = ch.getJSONObject(it)
            Channel(x.getString("i"), x.optInt("n"), x.getString("t"), x.optString("l").ifBlank { null }, x.getString("c"), x.getString("u"))
        }
        account = o.optJSONObject("acc")?.let { a ->
            AccountInfo(a.optBoolean("active"), a.optInt("max", 1), a.optLong("exp").takeIf { it > 0 },
                a.optInt("cons"), a.optLong("created").takeIf { it > 0 }, a.optString("status"), a.optString("user"))
        }
        playlist = p
        xtream = if (p.type == "xtream") XtreamApi(p.server, p.username, p.password, liveExt) else null
        vodCategories = emptyList(); seriesCategories = emptyList()
        moviesByCat.clear(); seriesByCat.clear(); seriesById.clear()
        cachedMovies = null; cachedSeries = null; movieInfoCache.clear(); seriesCache.clear()
        return channels.isNotEmpty()
    }

    private val background = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /** After an instant start: fetch the fresh list quietly and save it for next time. */
    fun refreshInBackground(c: android.content.Context, p: Playlist, liveExt: String) {
        val app = c.applicationContext
        background.launch {
            runCatching { load(p, liveExt); persist(app, liveExt) }
        }
    }
}
