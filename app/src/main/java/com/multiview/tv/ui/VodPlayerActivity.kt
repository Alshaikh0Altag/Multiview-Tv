package com.multiview.tv.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.multiview.tv.R
import com.multiview.tv.data.Library
import com.multiview.tv.data.Prefs
import com.multiview.tv.data.Settings
import org.json.JSONArray
import org.json.JSONObject
import com.multiview.tv.player.FrameRateMatcher
import com.multiview.tv.player.PlayerFactory
import com.multiview.tv.player.PlayerMenu

/**
 * Player for movies and episodes: seek bar, subtitles/audio from the controller,
 * resume position, and auto-play of the next episode.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VodPlayerActivity : BaseActivity() {

    data class Item(
        val key: String,
        val title: String,
        val url: String,
        val poster: String? = null,
        val seriesId: String? = null,
    )

    companion object {
        private const val EXTRA_ITEMS = "items"
        private const val EXTRA_START = "start"
        private const val EXTRA_RESUME = "resume"

        const val RESUME_ASK = 0
        const val RESUME_YES = 1
        const val RESUME_NO = 2

        fun intent(ctx: Context, items: List<Item>, start: Int, resume: Int = RESUME_ASK): Intent {
            val arr = JSONArray()
            items.forEach {
                arr.put(JSONObject().put("k", it.key).put("t", it.title).put("u", it.url)
                    .put("p", it.poster ?: "").put("s", it.seriesId ?: ""))
            }
            return Intent(ctx, VodPlayerActivity::class.java)
                .putExtra(EXTRA_ITEMS, arr.toString())
                .putExtra(EXTRA_START, start)
                .putExtra(EXTRA_RESUME, resume)
        }
    }

    private lateinit var playerView: PlayerView
    private lateinit var nameView: TextView
    private lateinit var seekView: TextView
    private lateinit var actionView: TextView
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private val hideSeek = Runnable { seekView.visibility = View.GONE }
    /** what OK does while the action pill is on screen */
    private var action: (() -> Unit)? = null
    private var introDismissedFor: String? = null
    private var nextCancelledFor: String? = null
    private var okPending = false
    private val tick = object : Runnable {
        override fun run() { updateActions(); ui.postDelayed(this, 1_000) }
    }
    private var player: ExoPlayer? = null
    private var items: List<Item> = emptyList()
    private var startIndex = 0
    private var askedResume = false
    private var resumeDirect = false
    private var afr: FrameRateMatcher? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_vod_player)
        playerView = findViewById(R.id.vodPlayerView)
        nameView = findViewById(R.id.vodName)
        seekView = findViewById(R.id.vodSeek)
        actionView = findViewById(R.id.vodAction)
        PlayerMenu.applySavedResizeMode(this, playerView)
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
            nameView.visibility = v
        })

        val arr = JSONArray(intent.getStringExtra(EXTRA_ITEMS) ?: "[]")
        items = (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Item(o.optString("k"), o.optString("t"), o.optString("u"),
                o.optString("p").ifBlank { null }, o.optString("s").ifBlank { null })
        }
        when (intent.getIntExtra(EXTRA_RESUME, RESUME_ASK)) {
            RESUME_YES -> { askedResume = true; resumeDirect = true }
            RESUME_NO -> askedResume = true
        }
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
        if (resumeDirect) {
            resumeDirect = false
            if (saved > 0) p.seekTo(saved)
            p.play()
        } else if (saved > 30_000 && !askedResume) {
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
        ui.post(tick)
    }

    // ---------- skip intro / next episode ----------

    private fun updateActions() {
        val p = player ?: return
        val cur = items.getOrNull(p.currentMediaItemIndex) ?: return
        val pos = p.currentPosition
        val dur = p.duration
        val isEpisode = cur.seriesId != null
        val introSec = Settings.introSkip(this)
        // pause at the end instead of auto-playing when the user turned it off or pressed Back on the countdown
        p.pauseAtEndOfMediaItems = !Settings.autoNext(this) || nextCancelledFor == cur.key
        when {
            isEpisode && introDismissedFor != cur.key && pos in 3_000 until introSec * 1000L - 5_000 -> {
                showAction(getString(R.string.skip_intro)) {
                    p.seekTo(introSec * 1000L); introDismissedFor = cur.key
                }
            }
            p.hasNextMediaItem() && dur > 0 && dur - pos in 0..20_000 && nextCancelledFor != cur.key -> {
                val sec = ((dur - pos) / 1000).coerceAtLeast(0)
                val text = if (Settings.autoNext(this)) getString(R.string.next_in, sec) else getString(R.string.next_episode)
                showAction(text) { p.seekToNextMediaItem(); p.play() }
            }
            else -> hideAction()
        }
    }

    private fun showAction(text: String, run: () -> Unit) {
        actionView.text = text
        actionView.visibility = View.VISIBLE
        action = run
    }

    private fun hideAction() {
        actionView.visibility = View.GONE
        action = null
    }

    private fun showSeek(text: String) {
        seekView.text = text
        seekView.visibility = View.VISIBLE
        ui.removeCallbacks(hideSeek)
        ui.postDelayed(hideSeek, 900)
    }

    override fun onStop() {
        super.onStop()
        ui.removeCallbacksAndMessages(null)
        afr?.detach()
        afr = null
        player?.let { p ->
            items.getOrNull(p.currentMediaItemIndex)?.let { cur ->
                startIndex = p.currentMediaItemIndex
                Prefs.savePosition(this, cur.key, p.currentPosition, p.duration)
                Library.saveProgress(this, cur.key, cur.title, cur.url, cur.poster, cur.seriesId,
                    p.currentPosition, p.duration)
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
        if (isScreenLocked) return super.dispatchKeyEvent(event)   // BaseActivity swallows keys while locked
        val k = event.keyCode
        if (k == KeyEvent.KEYCODE_MENU || k == KeyEvent.KEYCODE_INFO || k == KeyEvent.KEYCODE_SETTINGS) {
            if (event.action == KeyEvent.ACTION_UP) openMenu()
            return true
        }
        val ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
        // Long-press OK also opens the options (for remotes without a Menu key)
        if (ok && event.action == KeyEvent.ACTION_DOWN) {
            if (event.isLongPress) { okPending = false; openMenu(); return true }
            if (event.repeatCount > 0) return true
        }
        val p = player
        val controlsHidden = !playerView.isControllerFullyVisible
        // "Skip intro" / "Next episode" pill: OK runs it, Back dismisses it
        if (controlsHidden && action != null) {
            if (ok) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) okPending = true
                if (event.action == KeyEvent.ACTION_UP && okPending) { okPending = false; action?.invoke(); hideAction() }
                return true
            }
            if (k == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) {
                    val cur = p?.let { items.getOrNull(it.currentMediaItemIndex) }
                    if (cur != null) { introDismissedFor = cur.key; if (actionView.text.toString() != getString(R.string.skip_intro)) nextCancelledFor = cur.key }
                    hideAction()
                }
                return true
            }
        }
        // ◀ ▶ with the controls hidden: jump by the chosen step without opening the controls
        if (p != null && controlsHidden && (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val step = Settings.seekStep(this) * 1000L
                // in an Arabic (RTL) screen ◀ points forward
                val forward = (k == KeyEvent.KEYCODE_DPAD_RIGHT) xor (playerView.layoutDirection == View.LAYOUT_DIRECTION_RTL)
                val target = (p.currentPosition + if (forward) step else -step)
                    .coerceIn(0L, if (p.duration > 0) p.duration - 1_000L else Long.MAX_VALUE)
                p.seekTo(target)
                showSeek((if (forward) "+" else "−") + getString(R.string.seconds_n, Settings.seekStep(this)) + "   " + fmt(target))
            }
            return true
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
