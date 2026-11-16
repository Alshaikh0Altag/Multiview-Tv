package com.multiview.tv.ui

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.multiview.tv.R
import com.multiview.tv.data.Repository

/**
 * The side navigation shared by Home, Channels, Movies and Series.
 * Collapsed it shows icons; it widens with labels while it has focus.
 */
object Rail {

    private val ids = intArrayOf(
        R.id.railHome, R.id.railLive, R.id.railMovies, R.id.railSeries,
        R.id.railSearch, R.id.railMulti, R.id.railSettings,
    )

    fun setup(a: BaseActivity, selected: Int, beforeLeave: () -> Unit = {}) {
        val rail = a.findViewById<ViewGroup>(R.id.rail) ?: return
        ids.forEach { id -> a.findViewById<View>(id)?.isSelected = id == selected }

        fun go(cls: Class<*>, extra: (Intent) -> Unit = {}) {
            if (selected == R.id.railHome && cls == HomeActivity::class.java) return
            beforeLeave()
            val i = Intent(a, cls)
            extra(i)
            a.startActivity(i)
            // keep only Home underneath, so Back always returns there
            if (a !is HomeActivity) a.finish()
        }

        a.findViewById<View>(R.id.railHome).setOnClickListener {
            if (a !is HomeActivity) { beforeLeave(); a.finish() }
        }
        a.findViewById<View>(R.id.railLive).setOnClickListener {
            if (selected != R.id.railLive) go(MainActivity::class.java)
        }
        a.findViewById<View>(R.id.railMovies).setOnClickListener {
            if (!Repository.supportsVod) { Toast.makeText(a, R.string.vod_xtream_only, Toast.LENGTH_LONG).show(); return@setOnClickListener }
            if (selected != R.id.railMovies) go(VodActivity::class.java) { it.putExtra(VodActivity.EXTRA_MODE, VodActivity.MODE_MOVIES) }
        }
        a.findViewById<View>(R.id.railSeries).setOnClickListener {
            if (!Repository.supportsVod) { Toast.makeText(a, R.string.vod_xtream_only, Toast.LENGTH_LONG).show(); return@setOnClickListener }
            if (selected != R.id.railSeries) go(VodActivity::class.java) { it.putExtra(VodActivity.EXTRA_MODE, VodActivity.MODE_SERIES) }
        }
        a.findViewById<View>(R.id.railSearch).setOnClickListener { beforeLeave(); a.startActivity(Intent(a, SearchActivity::class.java)) }
        a.findViewById<View>(R.id.railMulti).setOnClickListener { beforeLeave(); a.startActivity(Intent(a, MultiViewActivity::class.java)) }
        a.findViewById<View>(R.id.railSettings).setOnClickListener { beforeLeave(); a.startActivity(Intent(a, SettingsActivity::class.java)) }

        // expand / collapse with focus
        val density = a.resources.displayMetrics.density
        var expanded = false
        rail.viewTreeObserver.addOnGlobalFocusChangeListener { _, _ ->
            val want = rail.hasFocus()
            if (want == expanded) return@addOnGlobalFocusChangeListener
            expanded = want
            rail.layoutParams = rail.layoutParams.apply { width = ((if (want) 240 else 80) * density).toInt() }
            for (i in 0 until rail.childCount) {
                (rail.getChildAt(i) as? ViewGroup)?.findViewWithTag<View>("label")?.visibility =
                    if (want) View.VISIBLE else View.GONE
            }
        }
    }

    /** Focus the selected rail item (used when a screen opens with nothing else to focus). */
    fun focusSelected(a: BaseActivity, selected: Int) {
        a.findViewById<View>(selected)?.requestFocus()
    }
}
