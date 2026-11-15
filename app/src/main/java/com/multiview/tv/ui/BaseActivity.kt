package com.multiview.tv.ui

import android.graphics.Typeface
import android.os.Bundle
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
    }

    private var clock: TextClock? = null

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
        applyClock()
    }

    /** Re-reads clock settings (called again after the Settings screen closes). */
    protected fun applyClock() {
        val c = clock ?: return
        val enabled = Settings.clockEnabled(this) && !isInPictureInPictureModeCompat()
        c.visibility = if (enabled) android.view.View.VISIBLE else android.view.View.GONE
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
        clock?.visibility = if (visible && Settings.clockEnabled(this)) android.view.View.VISIBLE else android.view.View.GONE
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
