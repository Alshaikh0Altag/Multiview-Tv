package com.multiview.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** In-memory store shared by all screens. */
object Repository {
    const val FAVORITES_ID = "__fav__"
    const val ALL_ID = "__all__"

    var playlist: Playlist? = null
        private set
    var account: AccountInfo? = null
        private set

    // live
    var categories: List<Category> = emptyList()
        private set
    var channels: List<Channel> = emptyList()
        private set

    // movies (loaded on first open)
    var vodCategories: List<Category> = emptyList()
        private set
    var movies: List<Movie> = emptyList()
        private set

    // series (loaded on first open)
    var seriesCategories: List<Category> = emptyList()
        private set
    var series: List<Series> = emptyList()
        private set

    private var xtream: XtreamApi? = null
    private val seriesCache = HashMap<String, SeriesDetails>()

    /** Four multiview slots; null = empty. */
    val multiView: Array<Channel?> = arrayOfNulls(4)

    val supportsVod: Boolean get() = xtream != null

    suspend fun load(p: Playlist, liveExt: String = "ts"): Unit = withContext(Dispatchers.IO) {
        playlist = p
        vodCategories = emptyList(); movies = emptyList()
        seriesCategories = emptyList(); series = emptyList()
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

    suspend fun loadMovies(): Unit = withContext(Dispatchers.IO) {
        val api = xtream ?: return@withContext
        if (movies.isNotEmpty()) return@withContext
        vodCategories = api.vodCategories()
        movies = api.vodStreams()
    }

    suspend fun loadSeries(): Unit = withContext(Dispatchers.IO) {
        val api = xtream ?: return@withContext
        if (series.isNotEmpty()) return@withContext
        seriesCategories = api.seriesCategories()
        series = api.seriesList()
    }

    suspend fun seriesDetails(s: Series): SeriesDetails = withContext(Dispatchers.IO) {
        seriesCache[s.id] ?: xtream!!.seriesInfo(s).also { seriesCache[s.id] = it }
    }

    /** @param excluded raw category ids hidden or still locked — left out of "all channels" */
    fun channelsIn(categoryId: String, favorites: Set<String>, excluded: Set<String> = emptySet()): List<Channel> = when (categoryId) {
        ALL_ID -> if (excluded.isEmpty()) channels else channels.filter { it.categoryId !in excluded }
        FAVORITES_ID -> channels.filter { favorites.contains(it.id) }
        else -> channels.filter { it.categoryId == categoryId }
    }

    fun addToMultiView(channel: Channel): Int {
        val idx = multiView.indexOfFirst { it == null }
        if (idx >= 0) multiView[idx] = channel
        return idx
    }

    fun maxConnections(): Int? = account?.maxConnections
}
