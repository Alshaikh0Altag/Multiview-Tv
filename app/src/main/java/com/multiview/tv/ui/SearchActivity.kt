package com.multiview.tv.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.multiview.tv.R
import com.multiview.tv.data.Channel
import com.multiview.tv.data.Movie
import com.multiview.tv.data.Repository
import com.multiview.tv.data.Series
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One search box for channels, movies and series. */
class SearchActivity : BaseActivity() {

    private sealed class Result(val key: String, val title: String, val image: String?) {
        class Live(val c: Channel) : Result("l:${c.id}", c.name, c.logo)
        class Film(val m: Movie) : Result("m:${m.id}", m.name, m.poster)
        class Show(val s: Series) : Result("s:${s.id}", s.name, s.cover)
    }

    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var results: RecyclerView
    private val adapter = ResultAdapter()
    private var job: Job? = null
    private var movies: List<Movie>? = null
    private var series: List<Series>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)
        input = findViewById(R.id.searchInput)
        status = findViewById(R.id.searchStatus)
        results = findViewById(R.id.searchResults)
        results.layoutManager = LinearLayoutManager(this)
        results.adapter = adapter

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = schedule()
        })
        input.requestFocus()

        // Movies and series libraries load in the background while you type
        if (Repository.supportsVod) {
            status.setText(R.string.search_loading_library)
            lifecycleScope.launch {
                movies = runCatching { Repository.allMovies() }.getOrDefault(emptyList())
                series = runCatching { Repository.allSeries() }.getOrDefault(emptyList())
                status.text = ""
                schedule()
            }
        }
    }

    private fun schedule() {
        job?.cancel()
        job = lifecycleScope.launch { delay(300); run() }
    }

    private fun run() {
        val q = input.text.toString().trim()
        if (q.length < 2) { adapter.items = emptyList(); return }
        val excludedLive = excludedCategories("l:")
        val excludedM = excludedCategories("m:")
        val excludedS = excludedCategories("s:")
        val out = ArrayList<Result>()
        Repository.channels.asSequence()
            .filter { it.categoryId !in excludedLive && it.name.contains(q, true) }
            .take(60).forEach { out += Result.Live(it) }
        movies?.asSequence()?.filter { it.categoryId !in excludedM && it.name.contains(q, true) }
            ?.take(60)?.forEach { out += Result.Film(it) }
        series?.asSequence()?.filter { it.categoryId !in excludedS && it.name.contains(q, true) }
            ?.take(60)?.forEach { out += Result.Show(it) }
        adapter.items = out
        status.text = when {
            Repository.supportsVod && movies == null -> getString(R.string.search_loading_library)
            out.isEmpty() -> getString(R.string.nothing_found)
            else -> getString(R.string.results_n, out.size)
        }
    }

    private fun open(r: Result) {
        when (r) {
            is Result.Live -> {
                val list = Repository.channelsIn(Repository.ALL_ID, emptySet(), excludedCategories("l:"))
                val idx = list.indexOfFirst { it.id == r.c.id }.coerceAtLeast(0)
                startActivity(Intent(this, PlayerActivity::class.java)
                    .putExtra(PlayerActivity.EXTRA_GROUP, Repository.ALL_ID)
                    .putExtra(PlayerActivity.EXTRA_INDEX, idx))
            }
            is Result.Film -> startActivity(MovieInfoActivity.intent(this, r.m))
            is Result.Show -> {
                Repository.registerSeries(listOf(r.s))
                startActivity(Intent(this, SeriesActivity::class.java).putExtra(SeriesActivity.EXTRA_ID, r.s.id))
            }
        }
    }

    private inner class ResultAdapter : RecyclerView.Adapter<ResultAdapter.VH>() {
        init { setHasStableIds(true) }

        var items: List<Result> = emptyList()
            set(value) { field = value; notifyDataSetChanged() }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val type: TextView = v.findViewById(R.id.number)
            val logo: ImageView = v.findViewById(R.id.logo)
            val name: TextView = v.findViewById(R.id.name)
        }

        override fun getItemId(position: Int) = items[position].key.hashCode().toLong()
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val h = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false))
            h.itemView.setOnClickListener { h.bindingAdapterPosition.takeIf { it >= 0 }?.let { open(items[it]) } }
            return h
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val r = items[position]
            h.name.text = r.title
            h.type.setText(when (r) {
                is Result.Live -> R.string.type_channel
                is Result.Film -> R.string.type_movie
                is Result.Show -> R.string.type_series
            })
            val fallback = when (r) { is Result.Live -> R.drawable.ic_tv; is Result.Film -> R.drawable.ic_movie; else -> R.drawable.ic_series }
            if (r.image != null) h.logo.load(r.image) {
                crossfade(false); error(fallback); transformations(RoundedCornersTransformation(8f))
            } else h.logo.setImageResource(fallback)
        }
    }
}
