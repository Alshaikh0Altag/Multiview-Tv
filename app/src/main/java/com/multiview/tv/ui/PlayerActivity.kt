package com.multiview.tv.ui

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.multiview.tv.R
import com.multiview.tv.data.Channel
import com.multiview.tv.data.Prefs
import com.multiview.tv.data.Repository
import com.multiview.tv.data.Settings
import com.multiview.tv.player.FrameRateMatcher
import com.multiview.tv.player.PlayerFactory
import com.multiview.tv.player.PlayerFactory.playUrl
import com.multiview.tv.player.PlayerMenu

/** Full-screen live TV. */
class PlayerActivity : BaseActivity() {

    companion object {
        const val EXTRA_GROUP = "group"
        const val EXTRA_INDEX = "index"
    }

    private lateinit var playerView: PlayerView
    private lateinit var overlay: View
    private lateinit var digitsView: TextView
    private lateinit var chNumber: TextView
    private lateinit var chName: TextView

    // channel list panel
    private lateinit var chPanel: View
    private lateinit var panelGroups: View
    private lateinit var panelTitle: TextView
    private lateinit var panelGroupList: RecyclerView
    private lateinit var panelChannelList: RecyclerView
    private lateinit var panelGroupAdapter: GroupAdapter
    private lateinit var panelChannelAdapter: ChannelAdapter
    private var group = Repository.ALL_ID
    private var panelGroup = Repository.ALL_ID
    private val hidePanel = Runnable { closePanel() }
    private val panelVisible get() = chPanel.visibility == View.VISIBLE

    private var player: ExoPlayer? = null
    private var afr: FrameRateMatcher? = null
    private var list: List<Channel> = emptyList()
    private var index = 0
    private var previousIndex = -1
    private var digits = ""
    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlay = Runnable { overlay.visibility = View.GONE }
    // ---- smart reconnect ----
    private var errorCount = 0
    private var currentUrl = ""
    private val retry = Runnable { player?.playUrl(currentUrl) }
    /** Still buffering after 15 s → reconnect instead of waiting forever. */
    private val stallCheck = Runnable {
        if (player?.playbackState == Player.STATE_BUFFERING) reconnect()
    }

    /** Alternative address for the same Xtream channel (.ts ↔ .m3u8). */
    private fun altUrl(url: String): String? = when {
        url.endsWith(".ts") && url.contains("/live/") -> url.removeSuffix(".ts") + ".m3u8"
        url.endsWith(".m3u8") && url.contains("/live/") -> url.removeSuffix(".m3u8") + ".ts"
        else -> null
    }

    private fun reconnect() {
        val c = list.getOrNull(index) ?: return
        errorCount++
        // 2nd and 3rd attempt: other stream format; then back to the original
        currentUrl = if (errorCount in 2..3) altUrl(c.url) ?: c.url else c.url
        val delayMs = (1_000L shl (errorCount - 1).coerceAtMost(4)).coerceAtMost(15_000L)
        digitsView.text = getString(R.string.reconnecting, errorCount)
        digitsView.visibility = View.VISIBLE
        handler.removeCallbacks(retry)
        handler.postDelayed(retry, delayMs)
    }
    private val commitDigits = Runnable { jumpToNumber() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.playerView)
        overlay = findViewById(R.id.overlay)
        digitsView = findViewById(R.id.digits)
        chNumber = findViewById(R.id.chNumber)
        chName = findViewById(R.id.chName)
        PlayerMenu.applySavedResizeMode(this, playerView)

        group = intent.getStringExtra(EXTRA_GROUP) ?: Repository.ALL_ID
        list = Repository.channelsIn(group, Prefs.favorites(this), excludedCategories("l:"))
        setupPanel()
        if (list.isEmpty()) { finish(); return }
        index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, list.size - 1)
    }

    override fun onStart() {
        super.onStart()
        if (list.isEmpty()) return
        val p = PlayerFactory.create(this)
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = reconnect()

            override fun onPlaybackStateChanged(state: Int) {
                handler.removeCallbacks(stallCheck)
                when (state) {
                    Player.STATE_BUFFERING -> handler.postDelayed(stallCheck, 15_000)
                    Player.STATE_READY -> {
                        if (errorCount > 0) digitsView.visibility = View.GONE
                        errorCount = 0
                    }
                }
            }
        })
        player = p
        playerView.player = p
        afr = FrameRateMatcher(this, p).also { it.attach() }
        tune(index)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacksAndMessages(null)
        afr?.detach()
        afr = null
        playerView.player = null
        player?.release()
        player = null
    }

    override fun onPictureInPictureModeChanged(isInPipMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPipMode, newConfig)
        if (isInPipMode) { overlay.visibility = View.GONE; closePanel() }
        setClockVisible(!isInPipMode)
    }

    private fun tune(i: Int) {
        if (list.isEmpty()) return
        val newIndex = (i + list.size) % list.size
        if (newIndex != index) previousIndex = index
        index = newIndex
        val c = list[index]
        handler.removeCallbacks(retry)
        handler.removeCallbacks(stallCheck)
        // Locked channel: ask for the PIN once per session
        val key = "c:${c.id}"
        if (c.id in Settings.lockedChannels(this) && key !in unlockedGroups) {
            player?.stop()
            chNumber.text = c.number.toString()
            chName.text = c.name
            showOverlay()
            askPin { unlockedGroups += key; tune(index) }
            return
        }
        errorCount = 0
        currentUrl = c.url
        player?.playUrl(c.url)
        Settings.setLastChannel(this, c.id)
        Settings.pushRecentChannel(this, c.id)
        Repository.recentIds = Settings.recentChannels(this)
        chNumber.text = c.number.toString()
        chName.text = c.name
        showOverlay()
    }

    // ---------- channel list panel (OK) ----------

    private fun setupPanel() {
        chPanel = findViewById(R.id.chPanel)
        panelGroups = findViewById(R.id.panelGroups)
        panelTitle = findViewById(R.id.panelTitle)
        panelGroupList = findViewById(R.id.panelGroupList)
        panelChannelList = findViewById(R.id.panelChannelList)

        panelGroupAdapter = GroupAdapter(
            onFocus = { g -> if (!g.locked && g.id != panelGroup) showPanelGroup(g.id, g.name) },
            onClick = { g ->
                withPin("l:${g.id}") {
                    showPanelGroup(g.id, g.name)
                    focusPanelChannel(0)
                }
            },
        )
        panelChannelAdapter = ChannelAdapter(
            onFocus = { _, _ -> },
            onClick = { _, pos ->
                // switch to the chosen group's list and tune
                group = panelGroup
                list = panelChannelAdapter.items
                index = -1
                tune(pos)
                closePanel()
            },
            onLongClick = { c, _ ->
                ChannelActions.show(this, c) { showPanelGroup(panelGroup, panelTitle.text.toString()) }
            },
        )
        panelGroupList.layoutManager = LinearLayoutManager(this)
        panelGroupList.adapter = panelGroupAdapter
        panelChannelList.layoutManager = LinearLayoutManager(this)
        panelChannelList.adapter = panelChannelAdapter
    }

    private fun buildPanelGroups() {
        val favs = Prefs.favorites(this)
        val hidden = Settings.hiddenGroups(this)
        val locked = Settings.lockedGroups(this)
        val items = mutableListOf(
            GroupItem(Repository.RECENT_ID, getString(R.string.recent_channels), Repository.recentIds.size),
            GroupItem(Repository.FAVORITES_ID, getString(R.string.favorite), Repository.channels.count { favs.contains(it.id) }),
            *Settings.customLists(this).map { (name, ids) ->
                GroupItem(Repository.LIST_PREFIX + name, "★ $name", ids.size)
            }.toTypedArray(),
            GroupItem(Repository.ALL_ID, getString(R.string.all_channels), -1),
        )
        val counts = Repository.channels.groupingBy { it.categoryId }.eachCount()
        Repository.categories.forEach { c ->
            val key = "l:${c.id}"
            val n = counts[c.id] ?: 0
            if (n > 0 && key !in hidden) items += GroupItem(c.id, c.name, n, locked = key in locked && key !in unlockedGroups)
        }
        panelGroupAdapter.items = items
    }

    private fun showPanelGroup(id: String, name: String) {
        panelGroup = id
        panelGroupAdapter.selectedId = id
        panelTitle.text = name
        panelChannelAdapter.favorites = Prefs.favorites(this)
        panelChannelAdapter.items = Repository.channelsIn(id, Prefs.favorites(this), excludedCategories("l:"))
    }

    private fun openPanel() {
        overlay.visibility = View.GONE
        buildPanelGroups()
        val name = panelGroupAdapter.items.firstOrNull { it.id == group }?.name ?: getString(R.string.all_channels)
        panelGroup = group
        panelGroupAdapter.selectedId = group
        panelTitle.text = name
        panelChannelAdapter.favorites = Prefs.favorites(this)
        panelChannelAdapter.items = list
        panelGroups.visibility = View.GONE
        chPanel.visibility = View.VISIBLE
        setClockVisible(false)
        focusPanelChannel(index.coerceAtLeast(0))
        bumpPanelTimer()
    }

    private fun focusPanelChannel(pos: Int) {
        val p = pos.coerceIn(0, (panelChannelAdapter.itemCount - 1).coerceAtLeast(0))
        (panelChannelList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(p, 200)
        panelChannelList.post {
            panelChannelList.findViewHolderForAdapterPosition(p)?.itemView?.requestFocus()
                ?: panelChannelList.getChildAt(0)?.requestFocus()
        }
    }

    private fun openGroupsColumn() {
        panelGroups.visibility = View.VISIBLE
        val p = panelGroupAdapter.positionOf(panelGroup).coerceAtLeast(0)
        panelGroupList.scrollToPosition(p)
        panelGroupList.post {
            panelGroupList.findViewHolderForAdapterPosition(p)?.itemView?.requestFocus()
                ?: panelGroupList.getChildAt(0)?.requestFocus()
        }
    }

    private fun closePanel() {
        handler.removeCallbacks(hidePanel)
        chPanel.visibility = View.GONE
        panelGroups.visibility = View.GONE
        setClockVisible(true)
    }

    private fun bumpPanelTimer() {
        handler.removeCallbacks(hidePanel)
        handler.postDelayed(hidePanel, 20_000)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!isScreenLocked && ::chPanel.isInitialized && panelVisible && event.action == KeyEvent.ACTION_DOWN) {
            bumpPanelTimer()
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    if (panelGroups.visibility == View.VISIBLE && panelGroupList.hasFocus()) {
                        // back from groups → channels
                        panelGroups.visibility = View.GONE
                        focusPanelChannel(0)
                    } else closePanel()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> if (panelChannelList.hasFocus()) { openGroupsColumn(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (panelGroupList.hasFocus()) { focusPanelChannel(0); return true }
            }
            return super.dispatchKeyEvent(event)
        }
        if (::chPanel.isInitialized && panelVisible) return super.dispatchKeyEvent(event)
        return super.dispatchKeyEvent(event)
    }

    private fun showOverlay() {
        overlay.visibility = View.VISIBLE
        handler.removeCallbacks(hideOverlay)
        handler.postDelayed(hideOverlay, 5_000)
    }

    // ----- number keys: type 1 0 5 → channel 105 -----

    private fun onDigit(d: Int) {
        if (digits.length >= 5) digits = ""
        digits += d
        digitsView.text = digits
        digitsView.visibility = View.VISIBLE
        handler.removeCallbacks(commitDigits)
        handler.postDelayed(commitDigits, 1_500)
    }

    private fun jumpToNumber() {
        val n = digits.toIntOrNull()
        digits = ""
        digitsView.visibility = View.GONE
        if (n == null) return
        val inList = list.indexOfFirst { it.number == n }
        if (inList >= 0) { tune(inList); return }
        // Not in this group: switch to "all channels"
        val all = Repository.channelsIn(Repository.ALL_ID, emptySet(), excludedCategories("l:"))
        val inAll = all.indexOfFirst { it.number == n }
        if (inAll >= 0) { list = all; index = -1; tune(inAll) }
        else Toast.makeText(this, getString(R.string.channel_not_found, n), Toast.LENGTH_SHORT).show()
    }

    private fun openMenu() {
        val p = player ?: return
        val c = list.getOrNull(index) ?: return
        PlayerMenu.show(this, p, playerView, c.url, extra = listOf(
            getString(R.string.open_multiview) to {
                if (Repository.multiView.none { it?.id == c.id }) {
                    if (Repository.multiView[0] == null) Repository.multiView[0] = c else Repository.addToMultiView(c)
                }
                startActivity(Intent(this, MultiViewActivity::class.java))
                finish()
            },
            getString(R.string.favorite_toggle) to {
                val added = Prefs.toggleFavorite(this, c.id)
                Toast.makeText(this, if (added) R.string.added_fav else R.string.removed_fav, Toast.LENGTH_SHORT).show()
            },
        ))
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (panelVisible) return super.onKeyDown(keyCode, event)
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) { onDigit(keyCode - KeyEvent.KEYCODE_0); return true }
        if (keyCode in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9) { onDigit(keyCode - KeyEvent.KEYCODE_NUMPAD_0); return true }
        when (keyCode) {
            // CH+ always goes to the next channel; the arrows follow the user's setting
            KeyEvent.KEYCODE_CHANNEL_UP -> { tune(index + 1); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { tune(index - 1); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { tune(if (Settings.reverseArrows(this)) index - 1 else index + 1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { tune(if (Settings.reverseArrows(this)) index + 1 else index - 1); return true }
            KeyEvent.KEYCODE_LAST_CHANNEL -> { if (previousIndex >= 0) tune(previousIndex); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                event.startTracking()   // so a long press can open the menu
                return true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> { openMenu(); return true }
            KeyEvent.KEYCODE_INFO -> { if (overlay.visibility == View.VISIBLE) overlay.visibility = View.GONE else showOverlay(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (panelVisible) return super.onKeyLongPress(keyCode, event)
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) { openMenu(); return true }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (panelVisible) return super.onKeyUp(keyCode, event)
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) && !event.isCanceled) {
            if (event.flags and KeyEvent.FLAG_CANCELED_LONG_PRESS == 0 && !panelVisible) openPanel()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }
}
