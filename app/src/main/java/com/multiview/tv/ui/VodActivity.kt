package com.multiview.tv.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.multiview.tv.R
import com.multiview.tv.data.Category
import com.multiview.tv.data.ContinueItem
import com.multiview.tv.data.Library
import com.multiview.tv.data.Movie
import com.multiview.tv.data.Repository
import com.multiview.tv.data.Series
import com.multiview.tv.data.Settings
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Movies or series library.
 * Special lists on top (continue watching, favorites, recently added, all),
 * then the provider's categories — each loaded only when opened, and cached.
 */
class VodActivity : BaseActivity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_MOVIES = "movies"
        const val MODE_SERIES = "series"

        private const val G_CONTINUE = "__continue__"
        private const val G_FAV = "__fav__"
        private const val G_RECENT = "__recent__"
        private const val G_ALL = "__all__"
    }

    private lateinit var mode: String
    private lateinit var groups: RecyclerView
    private lateinit var posters: RecyclerView
    private lateinit var search: EditText
    private lateinit var sortBtn: android.widget.Button
    private lateinit var loading: ProgressBar
    private lateinit var empty: TextView

    private lateinit var groupAdapter: GroupAdapter
    private lateinit var posterAdapter: PosterAdapter

    private var currentGroup: String? = null
    private var movies: List<Movie> = emptyList()
    private var seriesList: List<Series> = emptyList()
    private var continueItems: List<ContinueItem> = emptyList()
    private var loadJob: Job? = null

    private val prefix get() = if (mode == MODE_MOVIES) "m:" else "s:"
    private val isMovies get() = mode == MODE_MOVIES

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vod)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_MOVIES

        groups = findViewById(R.id.vodGroups)
        posters = findViewById(R.id.posters)
        search = findViewById(R.id.search)
        sortBtn = findViewById(R.id.sort)
        updateSortLabel()
        sortBtn.setOnClickListener {
            val next = when (Settings.vodSort(this)) { "new" -> "rating"; "rating" -> "name"; else -> "new" }
            Settings.setVodSort(this, next); updateSortLabel(); showItems()
        }
        loading = findViewById(R.id.vodLoading)
        empty = findViewById(R.id.vodEmpty)
        findViewById<TextView>(R.id.vodTitle).setText(if (isMovies) R.string.movies else R.string.series)
        Rail.setup(this, if (isMovies) R.id.railMovies else R.id.railSeries)

        groupAdapter = GroupAdapter(
            onFocus = { g -> if (!g.locked && g.id != currentGroup) openGroup(g.id, delayMs = 350) },
            onClick = { g -> withPin(prefix + g.id) { buildGroups(); openGroup(g.id, delayMs = 0) } },
            onLongClick = { g -> if (!g.id.startsWith("__")) pinMenu(g) },
        )
        posterAdapter = PosterAdapter(
            onClick = { open(it) },
            onLongClick = { p ->
                if (currentGroup == G_CONTINUE) continueItems.firstOrNull { it.key == p.id }?.let { c ->
                    ContinueActions.show(this, c) { currentGroup?.let { g -> reloadQuietly(g) } }
                }
            },
        )
        groups.layoutManager = LinearLayoutManager(this)
        groups.adapter = groupAdapter
        posters.layoutManager = GridLayoutManager(this, 5)
        posters.adapter = posterAdapter
        posters.setItemViewCacheSize(24)

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = showItems()
        })

        loadCategories()
    }

    override fun onResume() {
        super.onResume()
        // Continue watching / favorites change after playing or on the info screen
        if (currentGroup == G_CONTINUE || currentGroup == G_FAV) currentGroup?.let { reloadQuietly(it) }
    }

    /** Long-press a category: show its newest titles on the home screen. */
    private fun pinMenu(g: GroupItem) {
        val key = (if (isMovies) "m:" else "s:") + g.id
        val pinned = Settings.pinnedVod(this)
        val isPinned = key in pinned
        AlertDialog.Builder(this)
            .setTitle(g.name)
            .setItems(arrayOf(getString(if (isPinned) R.string.unpin_home else R.string.pin_home))) { _, _ ->
                if (isPinned) pinned.remove(key) else pinned[key] = g.name
                Settings.setPinnedVod(this, pinned)
                Toast.makeText(this, if (isPinned) R.string.unpinned else R.string.pinned, Toast.LENGTH_SHORT).show()
                buildGroups()
            }
            .showWrapped()
    }

    private fun categories(): List<Category> =
        if (isMovies) Repository.vodCategories else Repository.seriesCategories

    private fun continueList() = Library.continueWatching(this, if (isMovies) "m:" else "e:")

    private fun loadCategories() {
        setLoading(true)
        lifecycleScope.launch {
            try {
                if (isMovies) Repository.loadVodCategories() else Repository.loadSeriesCategories()
                buildGroups()
                val first = if (continueList().isNotEmpty()) G_CONTINUE
                    else groupAdapter.items.firstOrNull { !it.locked && !it.id.startsWith("__") }?.id ?: G_FAV
                openGroup(first, delayMs = 0)
                groups.post {
                    val pos = groupAdapter.positionOf(first).coerceAtLeast(0)
                    groups.scrollToPosition(pos)
                    groups.post { groups.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() }
                }
            } catch (e: Exception) {
                showError(e)
            }
        }
    }

    private fun buildGroups() {
        val hidden = Settings.hiddenGroups(this)
        val locked = Settings.lockedGroups(this)
        val pinnedKeys = Settings.pinnedVod(this).keys
        val special = listOf(
            GroupItem(G_CONTINUE, getString(R.string.continue_watching), -1),
            GroupItem(G_FAV, getString(R.string.favorite), -1),
            GroupItem(G_RECENT, getString(R.string.recently_added), -1),
            GroupItem(G_ALL, getString(R.string.all), -1),
        )
        groupAdapter.items = special + categories()
            .filter { prefix + it.id !in hidden }
            .map { c ->
                val key = prefix + c.id
                val pin = if ((if (isMovies) "m:" else "s:") + c.id in pinnedKeys) "📌 " else ""
                GroupItem(c.id, pin + c.name, count = -1, locked = key in locked && key !in unlockedGroups)
            }
        groupAdapter.selectedId = currentGroup
    }

    /** Hidden or still-locked categories are left out of "all" and "recently added". */
    private fun allowed(categoryId: String) = categoryId !in excludedCategories(prefix)

    /** Loads one list (debounced while the remote scrolls through the groups). */
    private fun openGroup(id: String, delayMs: Long) {
        currentGroup = id
        groupAdapter.selectedId = id
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            if (delayMs > 0) delay(delayMs)
            setLoading(true)
            try {
                fetch(id)
                if (currentGroup == id) showItems()
            } catch (e: Exception) {
                if (currentGroup == id) showError(e)
            }
        }
    }

    private fun reloadQuietly(id: String) {
        lifecycleScope.launch {
            try { fetch(id); if (currentGroup == id) showItems(keepPosition = true) } catch (_: Exception) {}
        }
    }

    private suspend fun fetch(id: String) {
        continueItems = emptyList()
        when (id) {
            G_CONTINUE -> continueItems = continueList()
            G_FAV -> if (isMovies) movies = Library.favoriteMovies(this)
                     else seriesList = Library.favoriteSeries(this).also { Repository.registerSeries(it) }
            G_RECENT -> if (isMovies) movies = Repository.recentMovies().filter { allowed(it.categoryId) }
                        else seriesList = Repository.recentSeries().filter { allowed(it.categoryId) }
            G_ALL -> if (isMovies) movies = Repository.allMovies().filter { allowed(it.categoryId) }
                     else seriesList = Repository.allSeries().filter { allowed(it.categoryId) }
            else -> if (isMovies) movies = Repository.moviesIn(id) else seriesList = Repository.seriesIn(id)
        }
    }

    private fun updateSortLabel() {
        sortBtn.setText(when (Settings.vodSort(this)) {
            "rating" -> R.string.sort_rating; "name" -> R.string.sort_name; else -> R.string.sort_new })
    }

    private fun ratingOf(r: String?): Double = r?.replace(',', '.')?.toDoubleOrNull() ?: -1.0

    private fun showItems(keepPosition: Boolean = false) {
        val sort = Settings.vodSort(this)
        val q = search.text.toString().trim()
        val match: (String) -> Boolean = { q.isEmpty() || it.contains(q, ignoreCase = true) }
        val items = when {
            currentGroup == G_CONTINUE -> continueItems.filter { match(it.title) }.map {
                PosterItem(it.key, it.title, it.poster, getString(R.string.minutes_left, it.remainingMin))
            }
            isMovies -> movies.asSequence().distinctBy { it.id }.filter { match(it.name) }
                .let { sq -> when (sort) {
                    "rating" -> sq.sortedByDescending { ratingOf(it.rating) }
                    "name" -> sq.sortedBy { it.name.lowercase() }
                    else -> sq.sortedByDescending { it.added } } }
                .map { PosterItem(it.id, it.name, it.poster, it.rating?.let { r -> "★ $r" }) }.toList()
            else -> seriesList.asSequence().distinctBy { it.id }.filter { match(it.name) }
                .let { sq -> when (sort) {
                    "rating" -> sq.sortedByDescending { ratingOf(it.rating) }
                    "name" -> sq.sortedBy { it.name.lowercase() }
                    else -> sq.sortedByDescending { it.added } } }
                .map { PosterItem(it.id, it.name, it.cover, it.rating?.let { r -> "★ $r" }) }.toList()
        }
        setLoading(false)
        posterAdapter.items = items
        if (!keepPosition) posters.scrollToPosition(0)
        empty.text = getString(if (currentGroup == G_FAV) R.string.no_favorites
            else if (currentGroup == G_CONTINUE) R.string.no_continue else R.string.nothing_found)
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun setLoading(on: Boolean) {
        loading.visibility = if (on) View.VISIBLE else View.GONE
        if (on) {
            empty.visibility = View.GONE
            posterAdapter.items = emptyList()
        }
    }

    private fun showError(e: Exception) {
        setLoading(false)
        empty.text = getString(R.string.load_failed, e.message ?: e.javaClass.simpleName)
        empty.visibility = View.VISIBLE
    }

    private fun open(p: PosterItem) {
        if (currentGroup == G_CONTINUE) {
            val c = continueItems.firstOrNull { it.key == p.id } ?: return
            startActivity(VodPlayerActivity.intent(this,
                listOf(VodPlayerActivity.Item(c.key, c.title, c.url, c.poster, c.seriesId)), 0,
                VodPlayerActivity.RESUME_YES))
            return
        }
        if (isMovies) {
            val m = movies.firstOrNull { it.id == p.id } ?: return
            startActivity(MovieInfoActivity.intent(this, m))
        } else {
            seriesList.firstOrNull { it.id == p.id }?.let { Repository.registerSeries(listOf(it)) }
            startActivity(Intent(this, SeriesActivity::class.java).putExtra(SeriesActivity.EXTRA_ID, p.id))
        }
    }
}
