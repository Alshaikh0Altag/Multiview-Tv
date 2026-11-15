package com.multiview.tv.ui

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.multiview.tv.R
import com.multiview.tv.data.Episode
import com.multiview.tv.data.Repository
import com.multiview.tv.data.SeriesDetails
import kotlinx.coroutines.launch

/** Series details: seasons on one side, episodes on the other. */
class SeriesActivity : BaseActivity() {

    companion object {
        const val EXTRA_ID = "series_id"
    }

    private lateinit var seasons: RecyclerView
    private lateinit var episodes: RecyclerView
    private lateinit var loading: ProgressBar

    private lateinit var seasonAdapter: GroupAdapter
    private lateinit var episodeAdapter: EpisodeAdapter

    private var details: SeriesDetails? = null
    private var currentEpisodes: List<Episode> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_series)

        val id = intent.getStringExtra(EXTRA_ID)
        val s = Repository.series.firstOrNull { it.id == id }
        if (s == null) { finish(); return }

        seasons = findViewById(R.id.seasons)
        episodes = findViewById(R.id.episodes)
        loading = findViewById(R.id.seriesLoading)
        findViewById<TextView>(R.id.seriesName).text = s.name
        findViewById<TextView>(R.id.seriesPlot).text = s.plot ?: ""
        s.cover?.let { findViewById<ImageView>(R.id.cover).load(it) }

        seasonAdapter = GroupAdapter { g -> showSeason(g.id.toInt()) }
        episodeAdapter = EpisodeAdapter { pos -> play(pos) }
        seasons.layoutManager = LinearLayoutManager(this)
        seasons.adapter = seasonAdapter
        episodes.layoutManager = LinearLayoutManager(this)
        episodes.adapter = episodeAdapter

        lifecycleScope.launch {
            try {
                val d = Repository.seriesDetails(s)
                details = d
                d.plot?.let { findViewById<TextView>(R.id.seriesPlot).text = it }
                if (s.cover == null) d.cover?.let { findViewById<ImageView>(R.id.cover).load(it) }
                seasonAdapter.items = d.seasons.map { (n, eps) ->
                    GroupItem(n.toString(), getString(R.string.season_n, n), eps.size)
                }
                d.seasons.keys.firstOrNull()?.let { showSeason(it) }
                seasons.post { seasons.getChildAt(0)?.requestFocus() }
            } catch (e: Exception) {
                Toast.makeText(this@SeriesActivity, getString(R.string.load_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
            } finally {
                loading.visibility = View.GONE
            }
        }
    }

    private fun showSeason(n: Int) {
        val d = details ?: return
        seasonAdapter.selectedId = n.toString()
        currentEpisodes = d.seasons[n].orEmpty()
        episodeAdapter.items = currentEpisodes.map {
            val sub = listOfNotNull(it.duration, it.plot).joinToString(" · ").ifBlank { null }
            EpisodeItem("${getString(R.string.episode_n, it.number)} — ${it.title}", sub)
        }
    }

    /** Plays from the chosen episode; the rest of the season follows automatically. */
    private fun play(pos: Int) {
        val name = details?.series?.name ?: ""
        val items = currentEpisodes.map {
            VodPlayerActivity.Item("e:${it.id}", "$name — ${getString(R.string.episode_n, it.number)}", it.url)
        }
        startActivity(VodPlayerActivity.intent(this, items, pos))
    }
}
