package com.multiview.tv.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.multiview.tv.R
import com.multiview.tv.data.Channel
import com.multiview.tv.data.Prefs
import com.multiview.tv.data.Repository
import com.multiview.tv.data.Settings
import com.multiview.tv.player.PlayerFactory
import com.multiview.tv.player.PlayerFactory.playUrl

class MainActivity : BaseActivity() {

    companion object {
        /** Auto-play the last channel only once per app launch. */
        private var autoplayDone = false
    }

    private lateinit var groups: RecyclerView
    private lateinit var channels: RecyclerView
    private lateinit var preview: PlayerView
    private lateinit var chTitle: TextView

    private lateinit var groupAdapter: GroupAdapter
    private lateinit var channelAdapter: ChannelAdapter

    private var player: ExoPlayer? = null
    private var currentGroup = Repository.ALL_ID
    private var focusedChannel: Channel? = null
    private val handler = Handler(Looper.getMainLooper())
    private val previewRunnable = Runnable { focusedChannel?.let { startPreview(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Repository.channels.isEmpty()) { goLogin(); return }
        setContentView(R.layout.activity_main)

        groups = findViewById(R.id.groups)
        channels = findViewById(R.id.channels)
        preview = findViewById(R.id.preview)
        chTitle = findViewById(R.id.chTitle)

        groupAdapter = GroupAdapter(
            onFocus = { g -> if (g.id != currentGroup) focusGroup(g) },
            onClick = { g -> withPin("l:${g.id}") { showGroup(g.id) } },
        )
        channelAdapter = ChannelAdapter(
            onFocus = { c, _ -> onChannelFocused(c) },
            onClick = { _, pos -> openPlayer(pos) },
            onLongClick = { c, _ -> addToMultiView(c) },
        )
        groups.layoutManager = LinearLayoutManager(this)
        groups.adapter = groupAdapter
        channels.layoutManager = LinearLayoutManager(this)
        channels.adapter = channelAdapter

        findViewById<Button>(R.id.openMovies).setOnClickListener { openVod(VodActivity.MODE_MOVIES) }
        findViewById<Button>(R.id.openSeries).setOnClickListener { openVod(VodActivity.MODE_SERIES) }
        findViewById<Button>(R.id.openMultiView).setOnClickListener { openMultiView() }
        findViewById<Button>(R.id.openSettings).setOnClickListener {
            releasePreview()
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        warnConnections()
        maybeAutoplay()
    }

    override fun onResume() {
        super.onResume()
        if (!::groupAdapter.isInitialized) return
        // Settings may have changed hidden / locked groups
        buildGroups()
        showGroup(if (isGroupOpen(currentGroup)) currentGroup else Repository.ALL_ID)
    }

    private fun isGroupOpen(id: String): Boolean {
        val key = "l:$id"
        return key !in Settings.hiddenGroups(this) && (key !in Settings.lockedGroups(this) || key in unlockedGroups)
    }

    private fun buildGroups() {
        val favs = Prefs.favorites(this)
        val hidden = Settings.hiddenGroups(this)
        val locked = Settings.lockedGroups(this)
        val counts = Repository.channels.groupingBy { it.categoryId }.eachCount()
        val excluded = excludedCategories("l:")
        val list = mutableListOf(
            GroupItem(Repository.FAVORITES_ID, getString(R.string.favorite), Repository.channels.count { favs.contains(it.id) }),
            GroupItem(Repository.ALL_ID, getString(R.string.all_channels),
                Repository.channels.count { it.categoryId !in excluded }),
        )
        Repository.categories.forEach { c ->
            val n = counts[c.id] ?: 0
            if (n > 0 && "l:${c.id}" !in hidden) {
                list += GroupItem(c.id, c.name, n, locked = "l:${c.id}" in locked && "l:${c.id}" !in unlockedGroups)
            }
        }
        groupAdapter.items = list
        groupAdapter.selectedId = currentGroup
    }

    /** Focus moves over a group: show it, unless it is locked (then wait for OK + PIN). */
    private fun focusGroup(g: GroupItem) {
        if (g.locked) {
            groupAdapter.selectedId = g.id
            channelAdapter.items = emptyList()
            chTitle.setText(R.string.group_locked)
            return
        }
        showGroup(g.id)
    }

    private fun showGroup(id: String) {
        val wasLocked = groupAdapter.items.any { it.id == id && it.locked }
        currentGroup = id
        if (wasLocked) buildGroups()   // refresh the lock icon after the PIN
        groupAdapter.selectedId = id
        val favs = Prefs.favorites(this)
        channelAdapter.favorites = favs
        channelAdapter.items = currentList(favs)
        channels.scrollToPosition(0)
    }

    private fun currentList(favs: Set<String> = Prefs.favorites(this)) =
        Repository.channelsIn(currentGroup, favs, excludedCategories("l:"))

    private fun onChannelFocused(c: Channel) {
        focusedChannel = c
        handler.removeCallbacks(previewRunnable)
        handler.postDelayed(previewRunnable, 700)   // debounce while scrolling
        chTitle.text = "${c.number}  ${c.name}"
    }

    private fun startPreview(c: Channel) {
        val p = player ?: PlayerFactory.create(this).also { player = it; preview.player = it }
        p.playUrl(c.url)
    }

    private fun openPlayer(position: Int) {
        releasePreview()
        startActivity(Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_GROUP, currentGroup)
            .putExtra(PlayerActivity.EXTRA_INDEX, position))
    }

    private fun maybeAutoplay() {
        if (autoplayDone || !Settings.autoplayLast(this)) return
        autoplayDone = true
        val id = Settings.lastChannel(this) ?: return
        val list = Repository.channelsIn(Repository.ALL_ID, emptySet(), excludedCategories("l:"))
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return
        startActivity(Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_GROUP, Repository.ALL_ID)
            .putExtra(PlayerActivity.EXTRA_INDEX, idx))
    }

    private fun addToMultiView(c: Channel) {
        val slot = Repository.addToMultiView(c)
        val msg = if (slot < 0) getString(R.string.multiview_full) else getString(R.string.added_to_slot, slot + 1)
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun openVod(mode: String) {
        if (!Repository.supportsVod) {
            Toast.makeText(this, R.string.vod_xtream_only, Toast.LENGTH_LONG).show()
            return
        }
        releasePreview()
        startActivity(Intent(this, VodActivity::class.java).putExtra(VodActivity.EXTRA_MODE, mode))
    }

    private fun openMultiView() {
        releasePreview()
        startActivity(Intent(this, MultiViewActivity::class.java))
    }

    private fun warnConnections() {
        val max = Repository.maxConnections() ?: return
        if (max < 2) Toast.makeText(this, getString(R.string.conn_warning, max), Toast.LENGTH_LONG).show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            focusedChannel?.let {
                val added = Prefs.toggleFavorite(this, it.id)
                Toast.makeText(this, if (added) R.string.added_fav else R.string.removed_fav, Toast.LENGTH_SHORT).show()
                channelAdapter.favorites = Prefs.favorites(this)
                channelAdapter.notifyDataSetChanged()
                buildGroups()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun releasePreview() {
        handler.removeCallbacks(previewRunnable)
        if (::preview.isInitialized) preview.player = null
        player?.release()
        player = null
    }

    override fun onStop() {
        super.onStop()
        releasePreview()
    }

    override fun onStart() {
        super.onStart()
        if (::channelAdapter.isInitialized) focusedChannel?.let { handler.postDelayed(previewRunnable, 700) }
    }

    private fun goLogin() {
        startActivity(Intent(this, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
