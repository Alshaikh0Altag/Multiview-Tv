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
import com.multiview.tv.data.SofaHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        override fun run() {
            load()
            val left = match.start - System.currentTimeMillis()
            when {
                match.isLive -> handler.postDelayed(this, 60_000)
                !match.isFinished && left in 0..3600_000L -> handler.postDelayed(this, left + 30_000)  // reload at kick-off
            }
        }
    }
    /** Countdown "00:42:10" during the last hour before kick-off. */
    private val tick = object : Runnable {
        override fun run() {
            val left = match.start - System.currentTimeMillis()
            if (!match.isLive && !match.isFinished && left in 1..3600_000L) {
                val s = left / 1000
                findViewById<TextView>(R.id.mdScore).text = "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
                findViewById<TextView>(R.id.mdStatus).setText(R.string.md_starts_in)
                handler.postDelayed(this, 1_000)
            }
        }
    }
    private var lastBroadcasters: List<String>? = null
    private val channelAdapter = ChannelCards()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        match = current ?: run { finish(); return }
        setContentView(R.layout.activity_match)

        val list = findViewById<RecyclerView>(R.id.mdChannels)
        list.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        list.adapter = channelAdapter
        updateChannelsSection()
        showHeader(match)
        showBroadcasters(match.broadcasts)
    }

    override fun onResume() {
        super.onResume()
        if (::match.isInitialized) { handler.post(refresh); handler.post(tick) }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresh)
        handler.removeCallbacks(tick)
    }

    private fun load() {
        lifecycleScope.launch {
            val d = runCatching { Matches.detailsAny(match) }.getOrNull() ?: run {
                if (findViewById<LinearLayout>(R.id.mdEvents).childCount == 0) addLine(R.id.mdEvents, getString(R.string.md_no_details))
                return@launch
            }
            match = d.match
            showHeader(d.match)
            showDetails(d)
            updateChannelsSection()
        }
    }

    /** Only the subscription channels that are the official broadcaster — not every sports channel. */
    private fun findChannels(broadcasters: List<String>) {
        val excluded = excludedCategories("l:")
        lifecycleScope.launch {
            val found = withContext(Dispatchers.Default) {
                runCatching {
                    Matches.broadcasterChannels(broadcasters, Repository.channelsIn(Repository.ALL_ID, emptySet(), excluded))
                }.getOrDefault(emptyList())
            }
            channelAdapter.items = found
            updateChannelsSection()
            val list = findViewById<RecyclerView>(R.id.mdChannels)
            if (found.isNotEmpty()) list.post { list.getChildAt(0)?.requestFocus() }
        }
    }

    /** No point offering channels once the match is over. */
    private fun updateChannelsSection() {
        val show = false   // broadcaster lookup turned off
        findViewById<View>(R.id.mdWatchTitle).visibility = if (show) View.VISIBLE else View.GONE
        findViewById<View>(R.id.mdChannels).visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showHeader(m: Match) {
        findViewById<TextView>(R.id.mdLeague).text = m.league
        findViewById<TextView>(R.id.mdHome).text = m.home.name
        findViewById<TextView>(R.id.mdAway).text = m.away.name
        findViewById<ImageView>(R.id.mdHomeLogo).load(m.home.logo) { addHeader("User-Agent", SofaHttp.UA) }
        findViewById<ImageView>(R.id.mdAwayLogo).load(m.away.logo) { addHeader("User-Agent", SofaHttp.UA) }
        val time = SimpleDateFormat(if (Settings.clock24h(this)) "HH:mm" else "h:mm a", Locale.US).format(Date(m.start))
        // score shown as home – away, read in the same order as the team names around it
        findViewById<TextView>(R.id.mdScore).text = when {
            m.sets.isNotEmpty() -> m.sets.joinToString("   ") { "${it.first}-${it.second}" }   // tennis sets
            m.isLive || m.isFinished -> "${m.home.score ?: 0} - ${m.away.score ?: 0}"
            else -> time
        }
        val st = findViewById<TextView>(R.id.mdStatus)
        st.text = when {
            m.isLive -> "● " + m.statusText
            m.isFinished -> m.statusText.takeIf { it == "أُلغيت" || it == "أُجّلت" } ?: getString(R.string.match_finished)
            else -> getString(R.string.md_kickoff)
        }
        st.setBackgroundResource(if (m.isLive) R.drawable.bg_badge_live else 0)
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun showDetails(d: MatchDetails) {
        findViewById<TextView>(R.id.mdVenue).text = d.venue ?: ""

        val events = findViewById<LinearLayout>(R.id.mdEvents)
        events.removeAllViews()
        if (d.match.sport == "tennis") {
            if (d.match.sets.isEmpty()) addLine(R.id.mdEvents, getString(R.string.md_not_started))
            d.match.sets.forEachIndexed { i, (h, a) ->
                addLine(R.id.mdEvents, getString(R.string.md_set_n, i + 1) + "   $h - $a", bold = true)
            }
        } else if (d.events.isEmpty()) {
            addLine(R.id.mdEvents, getString(if (d.match.state == "pre") R.string.md_not_started else R.string.md_no_events))
        } else d.events.forEach { e ->
            val icon = when (e.kind) { "yellow" -> "🟨"; "red" -> "🟥"; else -> "⚽" }
            val extra = when (e.kind) { "own_goal" -> " (${getString(R.string.md_own_goal)})"; "penalty" -> " (${getString(R.string.md_penalty)})"; else -> "" }
            val team = if (e.home) d.match.home.shortName else d.match.away.shortName
            addLine(R.id.mdEvents, "${e.minute}  $icon  ${e.player}$extra  ·  $team", bold = e.kind != "yellow" && e.kind != "red")
        }

        // referee and the two teams' places in the table
        findViewById<TextView>(R.id.mdReferee).apply {
            text = d.referee?.let { getString(R.string.md_referee, it) } ?: ""
            visibility = if (d.referee != null) View.VISIBLE else View.GONE
        }
        val table = findViewById<LinearLayout>(R.id.mdTable)
        table.removeAllViews()
        findViewById<TextView>(R.id.mdTableTitle).apply {
            text = getString(R.string.md_table) + (d.standingsTitle?.let { " — $it" } ?: "")
            visibility = if (d.standings.isNotEmpty()) View.VISIBLE else View.GONE
        }
        if (d.standings.isNotEmpty()) {
            addTableRow(table, "#", getString(R.string.md_team), getString(R.string.md_played), "+/-", getString(R.string.md_points), header = true)
            d.standings.forEach { r -> addTableRow(table, r.position.toString(), r.team, r.played.toString(), r.goalDiff, r.points.toString()) }
        }
        val full = findViewById<android.widget.Button>(R.id.mdFullTable)
        val ft = d.fullTable
        full.visibility = if (ft != null && ft.rows.size > d.standings.size) View.VISIBLE else View.GONE
        full.setOnClickListener { ft?.let { showFullTable(it, d.standings.mapNotNull { r -> r.teamId }.toSet()) } }

        val stats = findViewById<LinearLayout>(R.id.mdStats)
        stats.removeAllViews()
        if (d.stats.isEmpty()) addLine(R.id.mdStats, getString(R.string.md_no_stats))
        else d.stats.forEach { s -> addStat(stats, s.home, s.label, s.away) }

        showBroadcasters(d.broadcasters)
    }

    private fun showBroadcasters(list: List<String>) {
        findViewById<TextView>(R.id.mdBroadcast).visibility = View.GONE
        // small technical line (source + broadcaster lookup) to diagnose a missing broadcaster from a photo
        findViewById<TextView>(R.id.mdDiag).visibility = View.GONE
    }

    /** Whole league / group table in a dialog; the two teams of this match are highlighted. */
    private fun showFullTable(t: com.multiview.tv.data.StandingTable, highlight: Set<String>) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        addTableRow(box, "#", getString(R.string.md_team), getString(R.string.md_played), "+/-", getString(R.string.md_points), header = true)
        t.rows.forEach { r ->
            addTableRow(box, r.position.toString(), r.team, r.played.toString(), r.goalDiff, r.points.toString(),
                highlight = r.teamId in highlight)
        }
        // focusable so the remote can scroll a long table
        val scroll = android.widget.ScrollView(this).apply { isFocusable = true; addView(box) }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.md_table) + (t.title?.let { " — $it" } ?: ""))
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .showWrapped()
    }

    private fun addTableRow(parent: LinearLayout, pos: String, team: String, played: String, gd: String, pts: String,
                            header: Boolean = false, highlight: Boolean = false) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(6), dp(4), dp(6), dp(4))
            if (highlight) setBackgroundResource(R.drawable.bg_logo)
        }
        val color = if (header) R.color.muted else R.color.text
        val size = if (header) 13f else 16f
        fun cell(t: String, w: Float, g: Int) = TextView(this).apply {
            text = t; gravity = g; setTextColor(getColor(color)); setTextSize(TypedValue.COMPLEX_UNIT_SP, size); maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w)
        }
        row.addView(cell(pos, 0.6f, Gravity.CENTER))
        row.addView(cell(team, 3f, Gravity.START))
        row.addView(cell(played, 1f, Gravity.CENTER))
        row.addView(cell(gd, 1f, Gravity.CENTER))
        row.addView(cell(pts, 1f, Gravity.CENTER))
        parent.addView(row)
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
