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

    private var player: ExoPlayer? = null
    private var afr: FrameRateMatcher? = null
    private var list: List<Channel> = emptyList()
    private var index = 0
    private var previousIndex = -1
    private var digits = ""
    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlay = Runnable { overlay.visibility = View.GONE }
    private val retry = Runnable { list.getOrNull(index)?.let { player?.playUrl(it.url) } }
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

        val group = intent.getStringExtra(EXTRA_GROUP) ?: Repository.ALL_ID
        list = Repository.channelsIn(group, Prefs.favorites(this), excludedCategories("l:"))
        if (list.isEmpty()) { finish(); return }
        index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, list.size - 1)
    }

    override fun onStart() {
        super.onStart()
        if (list.isEmpty()) return
        val p = PlayerFactory.create(this)
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@PlayerActivity, R.string.stream_error, Toast.LENGTH_SHORT).show()
                handler.removeCallbacks(retry)
                handler.postDelayed(retry, 3_000)
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
        if (isInPipMode) overlay.visibility = View.GONE
        setClockVisible(!isInPipMode)
    }

    private fun tune(i: Int) {
        if (list.isEmpty()) return
        val newIndex = (i + list.size) % list.size
        if (newIndex != index) previousIndex = index
        index = newIndex
        val c = list[index]
        handler.removeCallbacks(retry)
        player?.playUrl(c.url)
        Settings.setLastChannel(this, c.id)
        chNumber.text = c.number.toString()
        chName.text = c.name
        showOverlay()
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
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) { onDigit(keyCode - KeyEvent.KEYCODE_0); return true }
        if (keyCode in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9) { onDigit(keyCode - KeyEvent.KEYCODE_NUMPAD_0); return true }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { tune(index - 1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { tune(index + 1); return true }
            KeyEvent.KEYCODE_LAST_CHANNEL -> { if (previousIndex >= 0) tune(previousIndex); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                event.startTracking()   // so a long press can open the menu
                return true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_SETTINGS -> { openMenu(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) { openMenu(); return true }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) && !event.isCanceled) {
            if (event.flags and KeyEvent.FLAG_CANCELED_LONG_PRESS == 0) {
                if (overlay.visibility == View.VISIBLE) overlay.visibility = View.GONE else showOverlay()
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }
}
