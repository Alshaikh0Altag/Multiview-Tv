package com.multiview.tv.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.multiview.tv.R
import com.multiview.tv.data.Channel
import com.multiview.tv.data.Prefs
import com.multiview.tv.data.Repository
import com.multiview.tv.data.Settings
import com.multiview.tv.player.PlayerFactory
import com.multiview.tv.player.PlayerFactory.playUrl

/**
 * Up to 4 live channels on one screen. Each tile owns its own ExoPlayer;
 * only the focused tile has sound.
 */
class MultiViewActivity : BaseActivity() {

    private class Tile(val root: View) {
        val playerView: PlayerView = root.findViewById(R.id.tilePlayer)
        val empty: TextView = root.findViewById(R.id.tileEmpty)
        val name: TextView = root.findViewById(R.id.tileName)
        val audio: TextView = root.findViewById(R.id.tileAudio)
        var player: ExoPlayer? = null
        var playingId: String? = null
    }

    private lateinit var tiles: List<Tile>
    private lateinit var row2: View
    private val slots get() = Repository.multiView
    private var audioSlot = 0
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_multiview)
        row2 = findViewById(R.id.row2)
        tiles = listOf(R.id.tile0, R.id.tile1, R.id.tile2, R.id.tile3).map { Tile(findViewById(it)) }

        tiles.forEachIndexed { i, t ->
            t.root.setOnFocusChangeListener { _, has -> if (has) setAudio(i) }
            // OK or long-press: options (add another channel, change, full screen, remove)
            t.root.setOnClickListener { if (slots[i] == null) pickChannel(i) else tileOptions(i) }
            t.root.setOnLongClickListener { if (slots[i] == null) pickChannel(i) else tileOptions(i); true }
        }
    }

    override fun onStart() {
        super.onStart()
        val hint = findViewById<View>(R.id.hint)
        hint.visibility = View.VISIBLE
        handler.postDelayed({ hint.animate().alpha(0f).setDuration(400).withEndAction { hint.visibility = View.GONE; hint.alpha = 1f } }, 6_000)
        refresh()
        tiles[audioSlot.coerceIn(0, visibleCount() - 1)].root.requestFocus()
        warnIfTooMany()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacksAndMessages(null)
        tiles.forEach { stop(it) }
    }

    /** Filled slots first. Only filled tiles are shown (one empty tile when nothing is playing). */
    private fun compact() {
        val filled = slots.filterNotNull()
        for (i in slots.indices) slots[i] = filled.getOrNull(i)
    }

    private fun visibleCount() = slots.count { it != null }.coerceIn(1, 4)

    private fun refresh() {
        compact()
        val v = visibleCount()
        row2.visibility = if (v >= 3) View.VISIBLE else View.GONE
        tiles.forEachIndexed { i, t ->
            t.root.visibility = if (i < v) View.VISIBLE else View.GONE
            val c = slots[i]
            if (c == null) {
                stop(t)
                t.playerView.visibility = View.GONE
                t.name.visibility = View.GONE
                t.empty.visibility = View.VISIBLE
            } else {
                t.empty.visibility = View.GONE
                t.playerView.visibility = View.VISIBLE
                t.name.visibility = View.VISIBLE
                t.name.text = c.name
                if (t.playingId != c.id) play(i, t, c)
            }
        }
        if (audioSlot >= v) audioSlot = 0
        setAudio(audioSlot)
    }

    private fun applyAudio(i: Int, p: ExoPlayer) {
        val on = i == audioSlot
        p.volume = if (on) 1f else 0f
        // Muted tiles don't decode audio at all — saves CPU and reduces stutter
        val disabled = p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO)
        if (disabled == on) {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !on)
                .build()
        }
    }

    private fun play(i: Int, t: Tile, c: Channel) {
        val p = t.player ?: PlayerFactory.create(this, lowLatency = true).also { player ->
            player.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    // retry this tile only
                    handler.postDelayed({ slots[i]?.let { ch -> t.player?.playUrl(ch.url) } }, 3_000)
                }
            })
            t.player = player
            t.playerView.player = player
        }
        applyAudio(i, p)
        p.playUrl(c.url)
        t.playingId = c.id
    }

    private fun stop(t: Tile) {
        t.playerView.player = null
        t.player?.release()
        t.player = null
        t.playingId = null
    }

    private fun setAudio(slot: Int) {
        audioSlot = slot
        tiles.forEachIndexed { i, t ->
            val on = i == slot && slots[i] != null
            t.player?.let { applyAudio(i, it) }
            t.audio.visibility = if (on) View.VISIBLE else View.GONE
        }
    }

    /** Two steps: group → channel. */
    private fun pickChannel(slot: Int) {
        val favs = Prefs.favorites(this)
        val groups = buildList {
            if (favs.isNotEmpty()) add(Repository.FAVORITES_ID to getString(R.string.favorite))
            add(Repository.ALL_ID to getString(R.string.all_channels))
            val hidden = Settings.hiddenGroups(this@MultiViewActivity)
            Repository.categories.filter { "l:${it.id}" !in hidden }.forEach { add(it.id to it.name) }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.pick_channel)
            .setItems(groups.map { it.second }.toTypedArray()) { _, gi ->
                withPin("l:${groups[gi].first}") { pickFromGroup(slot, groups[gi].first, groups[gi].second, favs) }
            }
            .show()
    }

    private fun pickFromGroup(slot: Int, groupId: String, groupName: String, favs: Set<String>) {
        val list = Repository.channelsIn(groupId, favs, excludedCategories("l:"))
        if (list.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(groupName)
            .setItems(list.map { "${it.number}  ${it.name}" }.toTypedArray()) { _, ci ->
                slots[slot] = list[ci]
                audioSlot = slot
                refresh()
                tiles[slot.coerceAtMost(visibleCount() - 1)].root.requestFocus()
                warnIfTooMany()
            }
            .show()
    }

    private fun tileOptions(slot: Int) {
        val c = slots[slot] ?: return
        val canAdd = slots.count { it != null } < 4
        val opts = listOfNotNull(
            if (canAdd) getString(R.string.add_channel) else null,
            getString(R.string.change_channel), getString(R.string.fullscreen), getString(R.string.remove),
            getString(R.string.menu_lock),
        )
        AlertDialog.Builder(this)
            .setTitle(c.name)
            .setItems(opts.toTypedArray()) { _, picked ->
                val which = if (canAdd) picked - 1 else picked
                when (which) {
                    -1 -> pickChannel(slots.indexOfFirst { it == null })
                    0 -> pickChannel(slot)
                    1 -> {
                        val idx = Repository.channelsIn(Repository.ALL_ID, emptySet(), excludedCategories("l:")).indexOfFirst { it.id == c.id }.coerceAtLeast(0)
                        startActivity(Intent(this, PlayerActivity::class.java)
                            .putExtra(PlayerActivity.EXTRA_GROUP, Repository.ALL_ID)
                            .putExtra(PlayerActivity.EXTRA_INDEX, idx))
                    }
                    3 -> lockScreen()
                    2 -> {
                        slots[slot] = null
                        refresh()   // compacts; tiles whose channel changed re-tune
                        audioSlot = 0
                        tiles[0].root.requestFocus()
                    }
                }
            }
            .show()
    }

    private fun warnIfTooMany() {
        val max = Repository.maxConnections() ?: return
        val used = slots.count { it != null }
        if (used > max) Toast.makeText(this, getString(R.string.conn_warning, max), Toast.LENGTH_LONG).show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            val focused = tiles.indexOfFirst { it.root.hasFocus() }
            if (focused >= 0) { if (slots[focused] != null) tileOptions(focused) else pickChannel(focused) }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
