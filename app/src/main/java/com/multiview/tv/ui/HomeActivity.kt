package com.multiview.tv.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.multiview.tv.R
import com.multiview.tv.data.Channel
import com.multiview.tv.data.ContinueItem
import com.multiview.tv.data.Library
import com.multiview.tv.data.Match
import com.multiview.tv.data.Matches
import com.multiview.tv.data.Movie
import com.multiview.tv.data.Prefs
import com.multiview.tv.data.Repository
import com.multiview.tv.data.Series
import com.multiview.tv.data.Settings
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Start screen: today's matches and the rows the user picked in Settings. */
class HomeActivity : BaseActivity() {

    companion object {
        /** Auto-play the last channel only once per app launch. */
        private var autoplayDone = false
    }

    // ----- cards -----
    sealed class Card(val key: String) {
        class MatchCard(val m: Match) : Card("match:${m.id}")
        class ChannelCard(val c: Channel, val group: String, val index: Int) : Card("ch:$group:${c.id}")
        class ContinueCard(val item: ContinueItem) : Card("cw:${item.key}")
        class MovieCard(val m: Movie) : Card("mv:${m.id}")
        class SeriesCard(val s: Series) : Card("sr:${s.id}")
        class Message(val text: String) : Card("msg:$text")
    }

    data class Row(val id: String, val title: String, val cards: List<Card>)

    private lateinit var rowsView: RecyclerView
    private val rowAdapter = RowAdapter()
    private var matches: List<Match>? = null
    /** Newest titles of the movie/series categories pinned to Home ("m:<id>" / "s:<id>"). */
    private val pinnedCards = HashMap<String, List<Card>>()
    private val pinnedLoading = HashSet<String>()
    private var signature = ""
    private val handler = Handler(Looper.getMainLooper())
    private val refreshMatches = object : Runnable {
        override fun run() { loadMatches(force = true); handler.postDelayed(this, 120_000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Repository.channels.isEmpty()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish(); return
        }
        setContentView(R.layout.activity_home)
        Rail.setup(this, R.id.railHome)
        rowsView = findViewById(R.id.homeRows)
        rowsView.layoutManager = LinearLayoutManager(this)
        rowsView.adapter = rowAdapter
        rowsView.setItemViewCacheSize(10)

        findViewById<TextView>(R.id.homeTitle).text = greeting()
        findViewById<TextView>(R.id.homeDate).text =
            SimpleDateFormat("EEEE d MMMM", Locale("ar")).format(Date())

        Repository.maxConnections()?.let { max ->
            if (max < 2) Toast.makeText(this, getString(R.string.conn_warning, max), Toast.LENGTH_LONG).show()
        }
        maybeAutoplay()
        checkForUpdates(manual = false)
    }

    override fun onResume() {
        super.onResume()
        if (!::rowsView.isInitialized) return
        buildRows()
        loadPinned()
        handler.removeCallbacks(refreshMatches)
        if ("matches" in Settings.homeRows(this)) handler.post(refreshMatches)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshMatches)
    }

    private fun greeting(): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return getString(if (h in 4..11) R.string.good_morning else R.string.good_evening)
    }

    private fun maybeAutoplay() {
        if (autoplayDone || !Settings.autoplayLast(this)) return
        autoplayDone = true
        val id = Settings.lastChannel(this) ?: return
        val list = Repository.channelsIn(Repository.ALL_ID, emptySet(), excludedCategories("l:"))
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) openChannel(Repository.ALL_ID, idx)
    }

    private fun loadMatches(force: Boolean) {
        lifecycleScope.launch {
            matches = runCatching { Matches.today(Settings.leagues(this@HomeActivity), force) }.getOrDefault(emptyList())
            buildRows()
        }
    }

    private fun loadPinned() {
        if (!Repository.supportsVod) return
        val pinned = Settings.pinnedVod(this).keys.filter { it !in pinnedCards && it !in pinnedLoading }
        if (pinned.isEmpty()) return
        pinnedLoading += pinned
        lifecycleScope.launch {
            for (key in pinned) {
                val cat = key.substring(2)
                val cards: List<Card> = runCatching {
                    if (key.startsWith("m:")) Repository.moviesIn(cat).sortedByDescending { it.added }.take(20).map { Card.MovieCard(it) }
                    else Repository.seriesIn(cat).sortedByDescending { it.added }.take(20).map { Card.SeriesCard(it) }
                }.getOrDefault(emptyList())
                pinnedCards[key] = cards
                pinnedLoading -= key
                buildRows()
            }
        }
    }

    // ---------- rows ----------

    private fun buildRows() {
        val favs = Prefs.favorites(this)
        val excluded = excludedCategories("l:")
        val rows = ArrayList<Row>()
        for (r in Settings.homeRows(this)) when (r) {
            "matches" -> {
                val m = matches
                val cards: List<Card> = when {
                    m == null -> listOf(Card.Message(getString(R.string.loading_matches)))
                    m.isEmpty() -> listOf(Card.Message(getString(R.string.no_matches)))
                    else -> m.map { Card.MatchCard(it) }
                }
                rows += Row("matches", getString(R.string.today_matches), cards)
            }
            "continue" -> {
                val items = (Library.continueWatching(this, "m:") + Library.continueWatching(this, "e:"))
                    .sortedByDescending { it.updated }.take(20)
                if (items.isNotEmpty()) rows += Row("continue", getString(R.string.continue_watching), items.map { Card.ContinueCard(it) })
            }
            "recent" -> channelRow(rows, "recent", getString(R.string.recent_channels), Repository.RECENT_ID, favs, excluded)
            "favorites" -> channelRow(rows, "favorites", getString(R.string.favorite_channels), Repository.FAVORITES_ID, favs, excluded)
            "lists" -> Settings.customLists(this).keys.forEach { name ->
                channelRow(rows, "list:$name", name, Repository.LIST_PREFIX + name, favs, excluded)
            }
            "fav_movies" -> Library.favoriteMovies(this).takeIf { it.isNotEmpty() }?.let {
                rows += Row("fav_movies", getString(R.string.favorite_movies), it.map { m -> Card.MovieCard(m) })
            }
            "fav_series" -> Library.favoriteSeries(this).takeIf { it.isNotEmpty() }?.let {
                Repository.registerSeries(it)
                rows += Row("fav_series", getString(R.string.favorite_series), it.map { s -> Card.SeriesCard(s) })
            }
        }
        // Pinned movie / series categories: their newest additions
        val lockedNow = Settings.lockedGroups(this) - unlockedGroups
        val hiddenG = Settings.hiddenGroups(this)
        for ((key, name) in Settings.pinnedVod(this)) {
            val groupKey = key   // same "m:" / "s:" prefix as the group lock keys
            if (groupKey in hiddenG || groupKey in lockedNow) continue
            val cards = pinnedCards[key]
            val title = getString(if (key.startsWith("m:")) R.string.newest_movies_in else R.string.newest_series_in, name)
            when {
                cards == null -> rows += Row("pin:$key", title, listOf(Card.Message(getString(R.string.loading_short))))
                cards.isNotEmpty() -> rows += Row("pin:$key", title, cards)
            }
        }
        if (rows.isEmpty()) rows += Row("empty", "", listOf(Card.Message(getString(R.string.home_empty))))

        // Only touch the views when something changed, so the remote's focus stays put
        val sig = rows.joinToString("|") { r -> r.id + ":" + r.cards.joinToString(",") { it.key } }
        if (sig == signature) { rowAdapter.refreshContent(rows); return }
        val first = signature.isEmpty()
        signature = sig
        rowAdapter.rows = rows
        if (first) rowsView.post { rowsView.findViewHolderForAdapterPosition(0)?.let { (it as RowAdapter.VH).focusFirst() } }
    }

    private fun channelRow(rows: MutableList<Row>, id: String, title: String, group: String, favs: Set<String>, excluded: Set<String>) {
        val list = Repository.channelsIn(group, favs, excluded)
        if (list.isEmpty()) return
        rows += Row(id, title, list.take(30).mapIndexed { i, c -> Card.ChannelCard(c, group, i) })
    }

    // ---------- actions ----------

    private fun openChannel(group: String, index: Int) {
        startActivity(Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_GROUP, group)
            .putExtra(PlayerActivity.EXTRA_INDEX, index))
    }

    private fun onCard(card: Card) {
        when (card) {
            is Card.ChannelCard -> {
                // resolve the index again: the list may have changed since the row was built
                val list = Repository.channelsIn(card.group, Prefs.favorites(this), excludedCategories("l:"))
                val idx = list.indexOfFirst { it.id == card.c.id }
                if (idx >= 0) openChannel(card.group, idx)
            }
            is Card.ContinueCard -> startActivity(VodPlayerActivity.intent(this,
                listOf(VodPlayerActivity.Item(card.item.key, card.item.title, card.item.url, card.item.poster, card.item.seriesId)),
                0, VodPlayerActivity.RESUME_YES))
            is Card.MovieCard -> startActivity(MovieInfoActivity.intent(this, card.m))
            is Card.SeriesCard -> startActivity(Intent(this, SeriesActivity::class.java).putExtra(SeriesActivity.EXTRA_ID, card.s.id))
            is Card.MatchCard -> openMatch(card.m)
            is Card.Message -> Unit
        }
    }

    private fun onCardLong(card: Card) {
        if (card is Card.ChannelCard) ChannelActions.show(this, card.c) { signature = ""; buildRows() }
    }

    /** Lists the channels that may show the match (team / broadcaster names, then sports channels). */
    private fun openMatch(m: Match) {
        val excluded = excludedCategories("l:")
        val all = Repository.channelsIn(Repository.ALL_ID, emptySet(), excluded)
        val candidates = Matches.candidateChannels(m, all, Repository.categories)
        if (candidates.isEmpty()) {
            Toast.makeText(this, R.string.match_no_channel, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SearchActivity::class.java))
            return
        }
        val title = "${m.home.shortName} × ${m.away.shortName}"
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.match_pick_channel, title))
            .setItems(candidates.map { it.name }.toTypedArray()) { _, i ->
                val idx = all.indexOfFirst { it.id == candidates[i].id }
                if (idx >= 0) openChannel(Repository.ALL_ID, idx)
            }
            .show()
    }

    private fun matchStatus(m: Match): String {
        val time = SimpleDateFormat(if (Settings.clock24h(this)) "HH:mm" else "h:mm a", Locale.US).format(Date(m.start))
        return when {
            m.isLive -> "● " + m.statusText.ifBlank { getString(R.string.live_badge) }
            m.isFinished -> getString(R.string.match_finished)
            else -> time
        }
    }

    // ---------- adapters ----------

    private inner class RowAdapter : RecyclerView.Adapter<RowAdapter.VH>() {
        init { setHasStableIds(true) }

        private var data: List<Row> = emptyList()
        var rows: List<Row>
            get() = data
            set(value) { data = value; notifyDataSetChanged() }

        /** Same rows and cards: just re-bind the texts (scores, times). */
        fun refreshContent(newRows: List<Row>) {
            data.indices.forEach { i ->
                (rowsView.findViewHolderForAdapterPosition(i) as? VH)?.cards?.update(newRows[i].cards)
            }
            data = newRows
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.rowTitle)
            val list: RecyclerView = v.findViewById(R.id.rowList)
            val cards = CardAdapter()
            init {
                list.layoutManager = LinearLayoutManager(v.context, LinearLayoutManager.HORIZONTAL, false)
                list.adapter = cards
            }
            fun focusFirst() { list.post { list.getChildAt(0)?.requestFocus() } }
        }

        override fun getItemId(position: Int) = rows[position].id.hashCode().toLong()
        override fun getItemCount() = rows.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_home_row, parent, false))

        override fun onBindViewHolder(h: VH, position: Int) {
            val r = rows[position]
            h.title.text = r.title
            h.title.visibility = if (r.title.isEmpty()) View.GONE else View.VISIBLE
            h.cards.items = r.cards
        }
    }

    private inner class CardAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        init { setHasStableIds(true) }

        private var data: List<Card> = emptyList()
        var items: List<Card>
            get() = data
            set(value) { data = value; notifyDataSetChanged() }

        /** Same keys → rebind in place (keeps focus); otherwise replace. */
        fun update(newItems: List<Card>) {
            if (newItems.map { it.key } == data.map { it.key }) {
                data = newItems
                notifyItemRangeChanged(0, newItems.size)
            } else items = newItems
        }

        override fun getItemId(position: Int) = items[position].key.hashCode().toLong()
        override fun getItemCount() = items.size
        override fun getItemViewType(position: Int) = when (items[position]) {
            is Card.MatchCard, is Card.Message -> 0
            is Card.ChannelCard -> 1
            else -> 2
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val layout = when (viewType) { 0 -> R.layout.item_card_match; 1 -> R.layout.item_card_channel; else -> R.layout.item_card_poster }
            val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
            val h = object : RecyclerView.ViewHolder(v) {}
            v.setOnClickListener { h.bindingAdapterPosition.takeIf { it >= 0 }?.let { onCard(items[it]) } }
            v.setOnLongClickListener { h.bindingAdapterPosition.takeIf { it >= 0 }?.let { onCardLong(items[it]) }; true }
            return h
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val v = h.itemView
            when (val c = items[position]) {
                is Card.MatchCard -> bindMatch(v, c.m)
                is Card.Message -> bindMessage(v, c.text)
                is Card.ChannelCard -> {
                    v.findViewById<TextView>(R.id.cName).text = c.c.name
                    val logo = v.findViewById<ImageView>(R.id.cLogo)
                    if (c.c.logo != null) logo.load(c.c.logo) { crossfade(false); error(R.drawable.ic_tv) } else logo.setImageResource(R.drawable.ic_tv)
                }
                is Card.ContinueCard -> bindPoster(v, c.item.title, c.item.poster,
                    if (c.item.duration > 0) (c.item.position * 100 / c.item.duration).toInt() else -1)
                is Card.MovieCard -> bindPoster(v, c.m.name, c.m.poster, -1)
                is Card.SeriesCard -> bindPoster(v, c.s.name, c.s.cover, -1)
            }
        }

        private fun bindPoster(v: View, title: String, image: String?, progress: Int) {
            v.findViewById<TextView>(R.id.pTitle).text = title
            val img = v.findViewById<ImageView>(R.id.pImage)
            if (image != null) img.load(image) { crossfade(false); error(R.drawable.ic_movie); transformations(RoundedCornersTransformation(14f)) }
            else img.setImageResource(R.drawable.ic_movie)
            val bar = v.findViewById<ProgressBar>(R.id.pProgress)
            bar.visibility = if (progress >= 0) View.VISIBLE else View.GONE
            if (progress >= 0) bar.progress = progress
        }

        private fun bindMessage(v: View, text: String) {
            v.findViewById<TextView>(R.id.mLeague).text = ""
            v.findViewById<TextView>(R.id.mStatus).text = ""
            v.findViewById<TextView>(R.id.mHome).text = text
            v.findViewById<TextView>(R.id.mAway).text = ""
            listOf(R.id.mHomeScore, R.id.mAwayScore).forEach { v.findViewById<TextView>(it).text = "" }
            listOf(R.id.mHomeLogo, R.id.mAwayLogo).forEach { v.findViewById<ImageView>(it).setImageDrawable(null) }
        }

        private fun bindMatch(v: View, m: Match) {
            v.findViewById<TextView>(R.id.mLeague).text = m.league
            val st = v.findViewById<TextView>(R.id.mStatus)
            st.text = matchStatus(m)
            st.setBackgroundResource(if (m.isLive) R.drawable.bg_badge_live else 0)
            v.findViewById<TextView>(R.id.mHome).text = m.home.name
            v.findViewById<TextView>(R.id.mAway).text = m.away.name
            val showScore = m.isLive || m.isFinished
            v.findViewById<TextView>(R.id.mHomeScore).text = if (showScore) m.home.score ?: "" else ""
            v.findViewById<TextView>(R.id.mAwayScore).text = if (showScore) m.away.score ?: "" else ""
            v.findViewById<ImageView>(R.id.mHomeLogo).load(m.home.logo) { crossfade(false) }
            v.findViewById<ImageView>(R.id.mAwayLogo).load(m.away.logo) { crossfade(false) }
        }
    }
}
