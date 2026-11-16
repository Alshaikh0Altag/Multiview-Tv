package com.multiview.tv.ui

import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextClock
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.multiview.tv.R
import com.multiview.tv.data.Http
import com.multiview.tv.data.Repository
import com.multiview.tv.data.Settings

/**
 * Shared behaviour for every screen:
 *  - on-screen clock (Settings → clock on/off, corner, 12/24h)
 *  - parental PIN prompt
 */
open class BaseActivity : AppCompatActivity() {

    companion object {
        /** Groups unlocked with the PIN during this session. */
        val unlockedGroups = HashSet<String>()

        /** App-wide, so the sleep timer keeps running while you move between screens. */
        val sleepHandler = Handler(Looper.getMainLooper())
        private var top: java.lang.ref.WeakReference<BaseActivity>? = null
        /** Closes the whole app from whichever screen is open when the timer ends. */
        private val sleepRunnable = Runnable { top?.get()?.finishAffinity() }
    }

    private var clock: TextClock? = null
    private var lockBadge: TextView? = null
    private val lockHandler = Handler(Looper.getMainLooper())


    private val hideLockBadge = Runnable { lockBadge?.visibility = View.GONE }

    /** Screen lock: the remote does nothing until OK is held down. */
    var isScreenLocked = false
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyGlobalSettings()
    }

    /** Settings that other parts of the app read from memory. */
    protected fun applyGlobalSettings() {
        Http.userAgent = Settings.userAgent(this).ifBlank { Http.DEFAULT_USER_AGENT }
        Repository.sortByName = Settings.channelSort(this) == "name"
        Repository.recentIds = Settings.recentChannels(this)
    }

    // ---------- sleep timer ----------


    fun sleepTimerDialog() {
        val minutes = intArrayOf(0, 15, 30, 60, 90, 120)
        val labels = minutes.map { if (it == 0) getString(R.string.off) else getString(R.string.minutes_n, it) }
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_sleep)
            .setItems(labels.toTypedArray()) { _, i ->
                sleepHandler.removeCallbacks(sleepRunnable)
                if (minutes[i] > 0) {
                    sleepHandler.postDelayed(sleepRunnable, minutes[i] * 60_000L)
                    Toast.makeText(this, getString(R.string.sleep_set, minutes[i]), Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        val root = findViewById<FrameLayout>(android.R.id.content) ?: return
        val pad = (12 * resources.displayMetrics.density).toInt()
        clock = TextClock(this).apply {
            format24Hour = "HH:mm"
            format12Hour = "hh:mm a"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(R.color.text))
            setBackgroundResource(R.drawable.bg_label)
            setPadding(pad, pad / 3, pad, pad / 3)
            elevation = 20f
            isFocusable = false
        }
        root.addView(clock)
        applyClock()
    }

    override fun onResume() {
        super.onResume()
        top = java.lang.ref.WeakReference(this)
        applyClock()
    }

    /** Re-reads clock settings (called again after the Settings screen closes). */
    protected fun applyClock() {
        val c = clock ?: return
        val enabled = Settings.clockEnabled(this) && !isInPictureInPictureModeCompat()
        c.visibility = if (enabled) View.VISIBLE else View.GONE
        c.alpha = Settings.clockOpacity(this) / 100f
        c.setTextSize(TypedValue.COMPLEX_UNIT_SP, Settings.clockSize(this).toFloat())
        val pad = (12 * resources.displayMetrics.density).toInt()
        if (Settings.clockStyle(this) == "plain") {
            c.background = null
            c.setPadding(0, 0, 0, 0)
            c.setShadowLayer(8f, 0f, 2f, android.graphics.Color.BLACK)   // readable on bright pictures
        } else {
            c.setBackgroundResource(R.drawable.bg_label)
            c.setPadding(pad, pad / 3, pad, pad / 3)
            c.setShadowLayer(0f, 0f, 0f, 0)
        }
        // TextClock follows the system 12/24h setting; force the user's choice
        val fmt = if (Settings.clock24h(this)) "HH:mm" else "hh:mm a"
        c.format12Hour = fmt
        c.format24Hour = fmt
        val m = (20 * resources.displayMetrics.density).toInt()
        val corner = if (Settings.clockPosition(this) == "left") Gravity.LEFT else Gravity.RIGHT
        c.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or corner,
        ).apply { setMargins(m, m, m, m) }
    }

    protected fun setClockVisible(visible: Boolean) {
        clock?.visibility = if (visible && Settings.clockEnabled(this)) View.VISIBLE else View.GONE
    }

    // ---------- screen lock ----------

    fun lockScreen() {
        isScreenLocked = true
        showLockBadge(getString(R.string.screen_locked))
    }

    private fun showLockBadge(text: String) {
        val root = findViewById<FrameLayout>(android.R.id.content) ?: return
        val badge = lockBadge ?: TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(getColor(R.color.text))
            setBackgroundResource(R.drawable.bg_label)
            val p = (14 * resources.displayMetrics.density).toInt()
            setPadding(p * 2, p, p * 2, p)
            elevation = 30f
            gravity = Gravity.CENTER
            root.addView(this, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ))
        }.also { lockBadge = it }
        badge.text = text
        badge.visibility = View.VISIBLE
        badge.bringToFront()
        lockHandler.removeCallbacks(hideLockBadge)
        lockHandler.postDelayed(hideLockBadge, 2_500)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!isScreenLocked) return super.dispatchKeyEvent(event)
        val ok = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER
        if (ok && event.action == KeyEvent.ACTION_DOWN && (event.isLongPress || event.repeatCount >= 15)) {
            isScreenLocked = false
            showLockBadge(getString(R.string.screen_unlocked))
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) showLockBadge(getString(R.string.screen_locked))
        return true   // swallow everything while locked (Back included)
    }

    private fun isInPictureInPictureModeCompat(): Boolean =
        android.os.Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode

    /** Category ids (without prefix) of [prefix] groups that are hidden or locked and not yet unlocked. */
    fun excludedCategories(prefix: String): Set<String> {
        val hidden = Settings.hiddenGroups(this)
        val locked = Settings.lockedGroups(this) - unlockedGroups
        return (hidden + locked).filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.toSet()
    }

    /** Runs [onOk] if the group is not locked, or after the right PIN is typed. */
    fun withPin(groupKey: String, locked: Set<String> = Settings.lockedGroups(this), onOk: () -> Unit) {
        if (groupKey !in locked || groupKey in unlockedGroups) { onOk(); return }
        askPin { unlockedGroups += groupKey; onOk() }
    }

    fun askPin(onOk: () -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = getString(R.string.pin_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.enter_pin)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (input.text.toString() == Settings.pin(this)) onOk()
                else Toast.makeText(this, R.string.wrong_pin, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
