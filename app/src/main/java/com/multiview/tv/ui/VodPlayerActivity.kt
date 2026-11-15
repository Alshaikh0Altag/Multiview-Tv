package com.multiview.tv.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.multiview.tv.R
import com.multiview.tv.data.Prefs
import com.multiview.tv.player.FrameRateMatcher
import com.multiview.tv.player.PlayerFactory
import com.multiview.tv.player.PlayerMenu

/**
 * Player for movies and episodes: seek bar, subtitles/audio from the controller,
 * resume position, and auto-play of the next episode.
 */
class VodPlayerActivity : BaseActivity() {

    data class Item(val key: String, val title: String, val url: String)

    companion object {
        private const val EXTRA_KEYS = "keys"
        private const val EXTRA_TITLES = "titles"
        private const val EXTRA_URLS = "urls"
        private const val EXTRA_START = "start"

        fun intent(ctx: Context, items: List<Item>, start: Int): Intent =
            Intent(ctx, VodPlayerActivity::class.java)
                .putExtra(EXTRA_KEYS, items.map { it.key }.toTypedArray())
                .putExtra(EXTRA_TITLES, items.map { it.title }.toTypedArray())
                .putExtra(EXTRA_URLS, items.map { it.url }.toTypedArray())
                .putExtra(EXTRA_START, start)
    }

    private lateinit var playerView: PlayerView
    private lateinit var nameView: TextView
    private var player: ExoPlayer? = null
    private var items: List<Item> = emptyList()
    private var startIndex = 0
    private var askedResume = false
    private var afr: FrameRateMatcher? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_vod_player)
        playerView = findViewById(R.id.vodPlayerView)
        nameView = findViewById(R.id.vodName)
        PlayerMenu.applySavedResizeMode(this, playerView)
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
            nameView.visibility = v
        })

        val keys = intent.getStringArrayExtra(EXTRA_KEYS).orEmpty()
        val titles = intent.getStringArrayExtra(EXTRA_TITLES).orEmpty()
        val urls = intent.getStringArrayExtra(EXTRA_URLS).orEmpty()
        items = keys.indices.map { Item(keys[it], titles.getOrElse(it) { "" }, urls.getOrElse(it) { "" }) }
        startIndex = intent.getIntExtra(EXTRA_START, 0).coerceIn(0, (items.size - 1).coerceAtLeast(0))
        if (items.isEmpty()) finish()
    }

    override fun onStart() {
        super.onStart()
        if (items.isEmpty()) return
        val p = PlayerFactory.create(this)
        player = p
        playerView.player = p
        afr = FrameRateMatcher(this, p).also { it.attach() }
        p.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                nameView.text = items.getOrNull(p.currentMediaItemIndex)?.title ?: ""
            }
        })
        p.setMediaItems(items.map { MediaItem.Builder().setUri(it.url).setMediaId(it.key).build() }, startIndex, 0L)
        nameView.text = items[startIndex].title
        p.prepare()

        val saved = Prefs.position(this, items[startIndex].key)
        if (saved > 30_000 && !askedResume) {
            askedResume = true
            AlertDialog.Builder(this)
                .setTitle(R.string.resume_title)
                .setPositiveButton(getString(R.string.resume_from, fmt(saved))) { _, _ -> p.seekTo(saved); p.play() }
                .setNegativeButton(R.string.start_over) { _, _ -> p.play() }
                .setOnCancelListener { p.play() }
                .show()
        } else {
            p.play()
        }
        playerView.requestFocus()
    }

    override fun onStop() {
        super.onStop()
        afr?.detach()
        afr = null
        player?.let { p ->
            items.getOrNull(p.currentMediaItemIndex)?.let { cur ->
                startIndex = p.currentMediaItemIndex
                Prefs.savePosition(this, cur.key, p.currentPosition, p.duration)
            }
            p.release()
        }
        playerView.player = null
        player = null
        askedResume = true   // came back from background: resume silently below
    }

    override fun onRestart() {
        super.onRestart()
        // onStart will rebuild the player; jump to where we stopped
        val key = items.getOrNull(startIndex)?.key ?: return
        val pos = Prefs.position(this, key)
        playerView.post { if (pos > 0) player?.seekTo(pos) }
    }

    override fun onPictureInPictureModeChanged(isInPipMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPipMode, newConfig)
        playerView.useController = !isInPipMode
        if (isInPipMode) nameView.visibility = android.view.View.GONE
        setClockVisible(!isInPipMode)
    }

    private fun openMenu() {
        val p = player ?: return
        val cur = items.getOrNull(p.currentMediaItemIndex) ?: return
        val extra = mutableListOf<Pair<String, () -> Unit>>()
        extra += getString(R.string.menu_speed) to { speed(p) }
        if (p.hasNextMediaItem()) extra += getString(R.string.next_episode) to { p.seekToNextMediaItem() }
        PlayerMenu.show(this, p, playerView, cur.url, extra)
    }

    private fun speed(p: ExoPlayer) {
        val speeds = floatArrayOf(0.75f, 1f, 1.25f, 1.5f, 2f)
        val labels = speeds.map { "${it}x" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_speed)
            .setSingleChoiceItems(labels, speeds.indexOfFirst { it == p.playbackParameters.speed }) { d, i ->
                p.setPlaybackSpeed(speeds[i]); d.dismiss()
            }
            .show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val k = event.keyCode
        if (k == KeyEvent.KEYCODE_MENU || k == KeyEvent.KEYCODE_INFO || k == KeyEvent.KEYCODE_SETTINGS) {
            if (event.action == KeyEvent.ACTION_UP) openMenu()
            return true
        }
        // Long-press OK also opens the options (for remotes without a Menu key)
        if ((k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) && event.action == KeyEvent.ACTION_DOWN) {
            if (event.isLongPress) { openMenu(); return true }
            if (event.repeatCount > 0) return true
        }
        // Let the PlayerView show the controller / handle seek & play-pause keys first
        return playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
        else "%d:%02d".format(s / 60, s % 60)
    }
}
