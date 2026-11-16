package com.multiview.tv.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.multiview.tv.R
import com.multiview.tv.data.Channel
import com.multiview.tv.data.Match
import com.multiview.tv.data.MatchDetails
import com.multiview.tv.data.Matches
import com.multiview.tv.data.Repository
import com.multiview.tv.data.Settings
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Match centre: score, goal scorers, cards, stats, broadcasters, and channels to watch it on. */
class MatchActivity : BaseActivity() {

    companion object {
        /** The match to show (set by the screen that opens this one). */
        var current: Match? = null
    }

    private lateinit var match: Match
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { load(); if (match.isLive) handler.postDelayed(this, 60_000) }
    }
    private val channelAdapter = ChannelCards()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        match = current ?: run { finish(); return }
        setContentView(R.layout.activity_match)

        val list = findViewById<RecyclerView>(R.id.mdChannels)
        list.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        list.adapter = channelAdapter
        val all = Repository.channelsIn(Repository.ALL_ID, emptySet(), excludedCategories("l:"))
        channelAdapter.items = Matches.candidateChannels(match, all, Repository.categories)
        list.post { list.getChildAt(0)?.requestFocus() }

        showHeader(match)
        showBroadcasters(match.broadcasts)
    }

    override fun onResume() {
        super.onResume()
        if (::match.isInitialized) handler.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresh)
    }

    private fun load() {
        lifecycleScope.launch {
            val d = runCatching { Matches.details(match) }.getOrNull() ?: run {
                if (findViewById<LinearLayout>(R.id.mdEvents).childCount == 0) addLine(R.id.mdEvents, getString(R.string.md_no_details))
                return@launch
            }
            match = d.match
            showHeader(d.match)
            showDetails(d)
        }
    }

    private fun showHeader(m: Match) {
        findViewById<TextView>(R.id.mdLeague).text = m.league
        findViewById<TextView>(R.id.mdHome).text = m.home.name
        findViewById<TextView>(R.id.mdAway).text = m.away.name
        findViewById<ImageView>(R.id.mdHomeLogo).load(m.home.logo)
        findViewById<ImageView>(R.id.mdAwayLogo).load(m.away.logo)
        val time = SimpleDateFormat(if (Settings.clock24h(this)) "HH:mm" else "h:mm a", Locale.US).format(Date(m.start))
        // score shown as home – away, read in the same order as the team names around it
        findViewById<TextView>(R.id.mdScore).text =
            if (m.isLive || m.isFinished) "${m.home.score ?: 0} - ${m.away.score ?: 0}" else time
        val st = findViewById<TextView>(R.id.mdStatus)
        st.text = when {
            m.isLive -> "● " + m.statusText
            m.isFinished -> getString(R.string.match_finished)
            else -> getString(R.string.md_kickoff)
        }
        st.setBackgroundResource(if (m.isLive) R.drawable.bg_badge_live else 0)
    }

    private fun showDetails(d: MatchDetails) {
        findViewById<TextView>(R.id.mdVenue).text = d.venue ?: ""

        val events = findViewById<LinearLayout>(R.id.mdEvents)
        events.removeAllViews()
        if (d.events.isEmpty()) {
            addLine(R.id.mdEvents, getString(if (d.match.state == "pre") R.string.md_not_started else R.string.md_no_events))
        } else d.events.forEach { e ->
            val icon = when (e.kind) { "yellow" -> "🟨"; "red" -> "🟥"; else -> "⚽" }
            val extra = when (e.kind) { "own_goal" -> " (${getString(R.string.md_own_goal)})"; "penalty" -> " (${getString(R.string.md_penalty)})"; else -> "" }
            val team = if (e.home) d.match.home.shortName else d.match.away.shortName
            addLine(R.id.mdEvents, "${e.minute}  $icon  ${e.player}$extra  ·  $team", bold = e.kind != "yellow" && e.kind != "red")
        }

        val stats = findViewById<LinearLayout>(R.id.mdStats)
        stats.removeAllViews()
        if (d.stats.isEmpty()) addLine(R.id.mdStats, getString(R.string.md_no_stats))
        else d.stats.forEach { s -> addStat(stats, s.home, s.label, s.away) }

        showBroadcasters(d.broadcasters)
    }

    private fun showBroadcasters(list: List<String>) {
        findViewById<TextView>(R.id.mdBroadcast).text =
            if (list.isEmpty()) "" else getString(R.string.md_broadcasters, list.joinToString("، "))
    }

    private fun addLine(containerId: Int, text: String, bold: Boolean = false) {
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(getColor(if (bold) R.color.text else R.color.muted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            if (bold) typeface = androidx.core.content.res.ResourcesCompat.getFont(this@MatchActivity, R.font.plex_medium)
            setPadding(0, dp(6), 0, dp(6))
        }
        findViewById<LinearLayout>(containerId).addView(tv)
    }

    private fun addStat(parent: LinearLayout, home: String, label: String, away: String) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(5), 0, dp(5)) }
        fun cell(t: String, weight: Float, g: Int, color: Int, size: Float) = TextView(this).apply {
            text = t; gravity = g; setTextColor(getColor(color)); setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
        }
        row.addView(cell(home, 1f, Gravity.START, R.color.text, 17f))
        row.addView(cell(label, 2f, Gravity.CENTER, R.color.muted, 14f))
        row.addView(cell(away, 1f, Gravity.END, R.color.text, 17f))
        parent.addView(row)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun play(c: Channel) {
        val all = Repository.channelsIn(Repository.ALL_ID, emptySet(), excludedCategories("l:"))
        val idx = all.indexOfFirst { it.id == c.id }
        if (idx < 0) return
        startActivity(Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_GROUP, Repository.ALL_ID)
            .putExtra(PlayerActivity.EXTRA_INDEX, idx))
    }

    private inner class ChannelCards : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        init { setHasStableIds(true) }
        var items: List<Channel> = emptyList()
            set(value) { field = value.distinctBy { it.id }; notifyDataSetChanged() }

        override fun getItemId(position: Int) = items[position].id.hashCode().toLong()
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_card_channel, parent, false)
            val h = object : RecyclerView.ViewHolder(v) {}
            v.setOnClickListener { h.bindingAdapterPosition.takeIf { it >= 0 }?.let { play(items[it]) } }
            return h
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val c = items[position]
            h.itemView.findViewById<TextView>(R.id.cName).text = c.name
            val logo = h.itemView.findViewById<ImageView>(R.id.cLogo)
            if (c.logo != null) logo.load(c.logo) { crossfade(false); error(R.drawable.ic_tv) } else logo.setImageResource(R.drawable.ic_tv)
        }
    }
}
