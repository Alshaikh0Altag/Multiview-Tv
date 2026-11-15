package com.multiview.tv.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.multiview.tv.R
import com.multiview.tv.data.Category
import com.multiview.tv.data.Movie
import com.multiview.tv.data.Repository
import com.multiview.tv.data.Series
import com.multiview.tv.data.Settings
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Movies or series library.
 * Loads the category list first, then only the category you open (cached),
 * instead of downloading the whole library at once.
 */
class VodActivity : BaseActivity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_MOVIES = "movies"
        const val MODE_SERIES = "series"
    }

    private lateinit var mode: String
    private lateinit var groups: RecyclerView
    private lateinit var posters: RecyclerView
    private lateinit var search: EditText
    private lateinit var loading: ProgressBar
    private lateinit var empty: TextView

    private lateinit var groupAdapter: GroupAdapter
    private lateinit var posterAdapter: PosterAdapter

    private var currentGroup: String? = null
    private var movies: List<Movie> = emptyList()
    private var seriesList: List<Series> = emptyList()
    private var loadJob: Job? = null

    private val prefix get() = if (mode == MODE_MOVIES) "m:" else "s:"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vod)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_MOVIES

        groups = findViewById(R.id.vodGroups)
        posters = findViewById(R.id.posters)
        search = findViewById(R.id.search)
        loading = findViewById(R.id.vodLoading)
        empty = findViewById(R.id.vodEmpty)
        findViewById<TextView>(R.id.vodTitle).setText(if (mode == MODE_MOVIES) R.string.movies else R.string.series)

        groupAdapter = GroupAdapter(
            onFocus = { g -> if (!g.locked && g.id != currentGroup) openGroup(g.id, delayMs = 350) },
            onClick = { g -> withPin(prefix + g.id) { buildGroups(); openGroup(g.id, delayMs = 0) } },
        )
        posterAdapter = PosterAdapter { open(it) }
        groups.layoutManager = LinearLayoutManager(this)
        groups.adapter = groupAdapter
        posters.layoutManager = GridLayoutManager(this, 6)
        posters.adapter = posterAdapter
        posters.setItemViewCacheSize(24)

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = showItems()
        })

        loadCategories()
    }

    private fun categories(): List<Category> =
        if (mode == MODE_MOVIES) Repository.vodCategories else Repository.seriesCategories

    private fun loadCategories() {
        setLoading(true)
        lifecycleScope.launch {
            try {
                if (mode == MODE_MOVIES) Repository.loadVodCategories() else Repository.loadSeriesCategories()
                buildGroups()
                // Open the first category that is not locked
                val first = groupAdapter.items.firstOrNull { !it.locked }
                if (first != null) openGroup(first.id, delayMs = 0) else setLoading(false)
                groups.post { groups.getChildAt(0)?.requestFocus() }
            } catch (e: Exception) {
                showError(e)
            }
        }
    }

    private fun buildGroups() {
        val hidden = Settings.hiddenGroups(this)
        val locked = Settings.lockedGroups(this)
        groupAdapter.items = categories()
            .filter { prefix + it.id !in hidden }
            .map { c ->
                val key = prefix + c.id
                GroupItem(c.id, c.name, count = -1, locked = key in locked && key !in unlockedGroups)
            }
        groupAdapter.selectedId = currentGroup
    }

    /** Loads one category (debounced while the remote scrolls through the list). */
    private fun openGroup(id: String, delayMs: Long) {
        currentGroup = id
        groupAdapter.selectedId = id
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            if (delayMs > 0) delay(delayMs)
            setLoading(true)
            try {
                if (mode == MODE_MOVIES) movies = Repository.moviesIn(id)
                else seriesList = Repository.seriesIn(id)
                if (currentGroup == id) showItems()
            } catch (e: Exception) {
                if (currentGroup == id) showError(e)
            }
        }
    }

    private fun showItems() {
        val q = search.text.toString().trim()
        val items = if (mode == MODE_MOVIES) {
            movies.asSequence()
                .filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }
                .map { PosterItem(it.id, it.name, it.poster, it.rating) }.toList()
        } else {
            seriesList.asSequence()
                .filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }
                .map { PosterItem(it.id, it.name, it.cover, it.rating) }.toList()
        }
        setLoading(false)
        posterAdapter.items = items
        posters.scrollToPosition(0)
        empty.text = getString(R.string.nothing_found)
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
        if (mode == MODE_MOVIES) {
            val m = movies.firstOrNull { it.id == p.id } ?: return
            startActivity(VodPlayerActivity.intent(this, listOf(VodPlayerActivity.Item("m:${m.id}", m.name, m.url)), 0))
        } else {
            startActivity(Intent(this, SeriesActivity::class.java).putExtra(SeriesActivity.EXTRA_ID, p.id))
        }
    }
}
