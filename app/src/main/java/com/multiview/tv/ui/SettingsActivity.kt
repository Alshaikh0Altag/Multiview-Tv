package com.multiview.tv.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.multiview.tv.R
import com.multiview.tv.data.Category
import com.multiview.tv.data.Prefs
import com.multiview.tv.data.Repository
import com.multiview.tv.data.Settings
import kotlinx.coroutines.launch

/** All app settings: playlists, clock, playback, groups, parental lock. */
class SettingsActivity : BaseActivity() {

    private lateinit var column: LinearLayout
    private val rows = mutableListOf<Pair<Button, () -> String>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(40)
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })

        header(R.string.settings, big = true)

        header(R.string.set_section_playlists)
        row({ getString(R.string.set_playlists, Prefs.load(this)?.let { label(it) } ?: "-") }) { playlists() }
        row({ getString(R.string.set_account) }) { accountInfo() }
        row({ getString(R.string.set_refresh) }) { switchTo(Prefs.activeIndex(this)) }

        header(R.string.set_section_clock)
        row({ getString(R.string.set_clock, onOff(Settings.clockEnabled(this))) }) {
            Settings.setClockEnabled(this, !Settings.clockEnabled(this)); applyClock()
        }
        row({ getString(R.string.set_clock_pos, getString(if (Settings.clockPosition(this) == "left") R.string.left else R.string.right)) }) {
            Settings.setClockPosition(this, if (Settings.clockPosition(this) == "left") "right" else "left"); applyClock()
        }
        row({ getString(R.string.set_clock_opacity, 100 - Settings.clockOpacity(this)) }) {
            val steps = listOf(100, 75, 50, 25)
            val next = steps[(steps.indexOf(Settings.clockOpacity(this)) + 1).mod(steps.size)]
            Settings.setClockOpacity(this, next); applyClock()
        }
        row({ getString(R.string.set_clock_fmt, if (Settings.clock24h(this)) "24" else "12") }) {
            Settings.setClock24h(this, !Settings.clock24h(this)); applyClock()
        }

        header(R.string.set_section_playback)
        row({ getString(R.string.set_max_quality, qualityLabel(Settings.maxHeight(this))) }) { maxQuality() }
        row({ getString(R.string.set_live_format, if (Settings.liveFormat(this) == "m3u8") "HLS (m3u8)" else "MPEG-TS") }) {
            Settings.setLiveFormat(this, if (Settings.liveFormat(this) == "m3u8") "ts" else "m3u8")
            switchTo(Prefs.activeIndex(this))   // reload channels with the new format
        }
        row({ getString(R.string.set_buffer, getString(when (Settings.bufferSize(this)) {
            "small" -> R.string.buffer_small; "large" -> R.string.buffer_large; else -> R.string.buffer_normal })) }) {
            val order = listOf("small", "normal", "large")
            Settings.setBufferSize(this, order[(order.indexOf(Settings.bufferSize(this)) + 1).mod(order.size)])
        }
        row({ getString(R.string.set_subtitle_size, Settings.subtitleSize(this)) }) {
            val steps = listOf(80, 100, 130, 160)
            Settings.setSubtitleSize(this, steps[(steps.indexOf(Settings.subtitleSize(this)) + 1).mod(steps.size)])
        }
        row({ getString(R.string.set_sort, getString(if (Settings.channelSort(this) == "name") R.string.sort_name else R.string.sort_number)) }) {
            Settings.setChannelSort(this, if (Settings.channelSort(this) == "name") "number" else "name")
            applyGlobalSettings()
        }
        row({ getString(R.string.set_user_agent, Settings.userAgent(this).ifBlank { getString(R.string.default_label) }) }) { userAgentDialog() }
        row({ getString(R.string.set_decoder, getString(if (Settings.decoder(this) == "sw") R.string.decoder_sw else R.string.decoder_hw)) }) {
            Settings.setDecoder(this, if (Settings.decoder(this) == "sw") "hw" else "sw")
        }
        row({ getString(R.string.set_afr, onOff(Settings.autoFrameRate(this))) }) {
            Settings.setAutoFrameRate(this, !Settings.autoFrameRate(this))
        }
        row({ getString(R.string.set_autoplay, onOff(Settings.autoplayLast(this))) }) {
            Settings.setAutoplayLast(this, !Settings.autoplayLast(this))
        }

        header(R.string.set_section_groups)
        row({ getString(R.string.set_hide_live) }) { pickGroups("l:", hide = true) }
        row({ getString(R.string.set_hide_movies) }) { pickGroups("m:", hide = true) }
        row({ getString(R.string.set_hide_series) }) { pickGroups("s:", hide = true) }

        header(R.string.set_section_parental)
        row({ getString(R.string.set_lock_live) }) { askPin { pickGroups("l:", hide = false) } }
        row({ getString(R.string.set_lock_movies) }) { askPin { pickGroups("m:", hide = false) } }
        row({ getString(R.string.set_lock_series) }) { askPin { pickGroups("s:", hide = false) } }
        row({ getString(R.string.set_change_pin) }) { askPin { changePin() } }

        rows.firstOrNull()?.first?.requestFocus()
    }

    // ---------- building blocks ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun header(res: Int, big: Boolean = false) {
        column.addView(TextView(this).apply {
            setText(res)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (big) 30f else 16f)
            setTextColor(getColor(if (big) R.color.text else R.color.muted))
            setPadding(0, dp(if (big) 0 else 22), 0, dp(8))
        })
    }

    private fun row(text: () -> String, action: () -> Unit) {
        val b = Button(this, null, 0, R.style.TvButton).apply {
            this.text = text()
            textAlignment = TextView.TEXT_ALIGNMENT_VIEW_START
        }
        b.setOnClickListener { action(); refresh() }
        column.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })
        rows += b to text
    }

    private fun refresh() = rows.forEach { (b, t) -> b.text = t() }

    private fun onOff(v: Boolean) = getString(if (v) R.string.on else R.string.off)

    private fun qualityLabel(h: Int) = if (h == 0) getString(R.string.quality_auto) else "${h}p"

    private fun label(p: com.multiview.tv.data.Playlist) =
        p.name.ifBlank { if (p.type == "xtream") p.username else p.m3uUrl.take(30) }

    // ---------- actions ----------

    private fun maxQuality() {
        val values = intArrayOf(0, 1080, 720, 480)
        AlertDialog.Builder(this)
            .setTitle(R.string.set_max_quality_title)
            .setSingleChoiceItems(values.map { qualityLabel(it) }.toTypedArray(), values.indexOf(Settings.maxHeight(this))) { d, i ->
                Settings.setMaxHeight(this, values[i]); refresh(); d.dismiss()
            }
            .show()
    }

    private fun playlists() {
        val list = Prefs.playlists(this)
        val names = list.mapIndexed { i, p -> (if (i == Prefs.activeIndex(this)) "✓ " else "") + label(p) } +
            getString(R.string.add_playlist)
        AlertDialog.Builder(this)
            .setTitle(R.string.set_section_playlists)
            .setItems(names.toTypedArray()) { _, i ->
                if (i == list.size) {
                    startActivity(Intent(this, LoginActivity::class.java).putExtra(LoginActivity.EXTRA_ADD, true))
                } else {
                    AlertDialog.Builder(this)
                        .setTitle(label(list[i]))
                        .setItems(arrayOf(getString(R.string.use_playlist), getString(R.string.remove))) { _, w ->
                            if (w == 0) switchTo(i) else { Prefs.removePlaylist(this, i); refresh() }
                        }
                        .show()
                }
            }
            .show()
    }

    private fun switchTo(index: Int) {
        Prefs.setActive(this, index)
        unlockedGroups.clear()
        Repository.multiView.fill(null)
        startActivity(Intent(this, LoginActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    /** hide = true → hidden groups, false → locked groups. */
    private fun pickGroups(prefix: String, hide: Boolean) {
        lifecycleScope.launch {
            val cats: List<Category> = try {
                when (prefix) {
                    "m:" -> { Repository.loadVodCategories(); Repository.vodCategories }
                    "s:" -> { Repository.loadSeriesCategories(); Repository.seriesCategories }
                    else -> Repository.categories
                }
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, getString(R.string.load_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                return@launch
            }
            if (cats.isEmpty()) {
                Toast.makeText(this@SettingsActivity, R.string.nothing_found, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val current = (if (hide) Settings.hiddenGroups(this@SettingsActivity) else Settings.lockedGroups(this@SettingsActivity)).toMutableSet()
            val checked = cats.map { "$prefix${it.id}" in current }.toBooleanArray()
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle(if (hide) R.string.set_hide_title else R.string.set_lock_title)
                .setMultiChoiceItems(cats.map { it.name }.toTypedArray(), checked) { _, i, on ->
                    val key = "$prefix${cats[i].id}"
                    if (on) current += key else current -= key
                }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    if (hide) Settings.setHiddenGroups(this@SettingsActivity, current)
                    else Settings.setLockedGroups(this@SettingsActivity, current)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun accountInfo() {
        val a = Repository.account
        val p = Prefs.load(this)
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val lines = if (a == null) {
            listOf(getString(R.string.acc_m3u))
        } else listOfNotNull(
            getString(R.string.acc_user, a.username.ifBlank { p?.username ?: "-" }),
            getString(R.string.acc_status, a.status.ifBlank { "-" }),
            a.expiresAt?.let { getString(R.string.acc_expires, fmt.format(java.util.Date(it))) }
                ?: getString(R.string.acc_expires, getString(R.string.acc_unlimited)),
            a.expiresAt?.let {
                val days = ((it - System.currentTimeMillis()) / 86_400_000L).coerceAtLeast(0)
                getString(R.string.acc_days_left, days)
            },
            getString(R.string.acc_connections, a.activeConnections, a.maxConnections),
            a.createdAt?.let { getString(R.string.acc_created, fmt.format(java.util.Date(it))) },
            p?.server?.takeIf { it.isNotBlank() }?.let { getString(R.string.acc_server, it) },
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.set_account)
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun userAgentDialog() {
        val input = EditText(this).apply {
            setText(Settings.userAgent(this@SettingsActivity))
            hint = com.multiview.tv.data.Http.DEFAULT_USER_AGENT
            textDirection = android.view.View.TEXT_DIRECTION_LTR
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.set_user_agent_title)
            .setMessage(R.string.set_user_agent_msg)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                Settings.setUserAgent(this, input.text.toString().trim())
                applyGlobalSettings()
                refresh()
            }
            .setNeutralButton(R.string.default_label) { _, _ ->
                Settings.setUserAgent(this, ""); applyGlobalSettings(); refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun changePin() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = getString(R.string.new_pin)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.set_change_pin)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val v = input.text.toString()
                if (v.length < 4) Toast.makeText(this, R.string.pin_too_short, Toast.LENGTH_SHORT).show()
                else { Settings.setPin(this, v); Toast.makeText(this, R.string.pin_saved, Toast.LENGTH_SHORT).show() }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
