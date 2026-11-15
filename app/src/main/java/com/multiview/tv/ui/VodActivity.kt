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
import com.multiview.tv.data.Repository
import com.multiview.tv.data.Settings
import kotlinx.coroutines.launch

/** Movies or series library: categories + poster grid + search. */
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

    private var currentGroup = Repository.ALL_ID

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
            onFocus = { g -> if (g.id != currentGroup && !g.locked) selectGroup(g.id) },
            onClick = { g -> withPin(prefix + g.id) { selectGroup(g.id); buildGroups() } },
        )
        posterAdapter = PosterAdapter { open(it) }
        groups.layoutManager = LinearLayoutManager(this)
        groups.adapter = groupAdapter
        posters.layoutManager = GridLayoutManager(this, 6)
        posters.adapter = posterAdapter

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = filter()
        })

        load()
    }

    private fun load() {
        loading.visibility = View.VISIBLE
        empty.visibility = View.GONE
        lifecycleScope.launch {
            try {
                if (mode == MODE_MOVIES) Repository.loadMovies() else Repository.loadSeries()
                buildGroups()
                filter()
                groups.post { groups.getChildAt(0)?.requestFocus() }
            } catch (e: Exception) {
                empty.text = getString(R.string.load_failed, e.message ?: e.javaClass.simpleName)
                empty.visibility = View.VISIBLE
            } finally {
                loading.visibility = View.GONE
            }
        }
    }

    private val prefix get() = if (mode == MODE_MOVIES) "m:" else "s:"

    private fun selectGroup(id: String) {
        currentGroup = id
        groupAdapter.selectedId = id
        filter()
    }

    private fun categories(): List<Category> =
        if (mode == MODE_MOVIES) Repository.vodCategories else Repository.seriesCategories

    private fun buildGroups() {
        val counts: Map<String, Int> = if (mode == MODE_MOVIES)
            Repository.movies.groupingBy { it.categoryId }.eachCount()
        else
            Repository.series.groupingBy { it.categoryId }.eachCount()
        val hidden = Settings.hiddenGroups(this)
        val locked = Settings.lockedGroups(this)
        val excluded = excludedCategories(prefix)
        val total = counts.filterKeys { it !in excluded }.values.sum()
        val list = mutableListOf(GroupItem(Repository.ALL_ID, getString(R.string.all), total))
        categories().forEach { c ->
            val key = prefix + c.id
            if (key in hidden) return@forEach
            counts[c.id]?.let { n -> list += GroupItem(c.id, c.name, n, locked = key in locked && key !in unlockedGroups) }
        }
        groupAdapter.items = list
        groupAdapter.selectedId = currentGroup
    }

    private fun filter() {
        val q = search.text.toString().trim()
        val excluded = excludedCategories(prefix)
        val inGroup: (String) -> Boolean = { cat ->
            if (currentGroup == Repository.ALL_ID) cat !in excluded else cat == currentGroup
        }
        val items = if (mode == MODE_MOVIES) {
            Repository.movies.asSequence()
                .filter { inGroup(it.categoryId) && (q.isEmpty() || it.name.contains(q, ignoreCase = true)) }
                .map { PosterItem(it.id, it.name, it.poster, it.rating) }.toList()
        } else {
            Repository.series.asSequence()
                .filter { inGroup(it.categoryId) && (q.isEmpty() || it.name.contains(q, ignoreCase = true)) }
                .map { PosterItem(it.id, it.name, it.cover, it.rating) }.toList()
        }
        posterAdapter.items = items
        posters.scrollToPosition(0)
        if (loading.visibility != View.VISIBLE) {
            empty.text = getString(R.string.nothing_found)
            empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun open(p: PosterItem) {
        if (mode == MODE_MOVIES) {
            val m = Repository.movies.firstOrNull { it.id == p.id } ?: return
            startActivity(VodPlayerActivity.intent(this, listOf(VodPlayerActivity.Item("m:${m.id}", m.name, m.url)), 0))
        } else {
            startActivity(Intent(this, SeriesActivity::class.java).putExtra(SeriesActivity.EXTRA_ID, p.id))
        }
    }
}
