package com.multiview.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.multiview.tv.R
import com.multiview.tv.data.Playlist
import com.multiview.tv.data.Prefs
import com.multiview.tv.data.Repository
import com.multiview.tv.data.Settings
import kotlinx.coroutines.launch

class LoginActivity : BaseActivity() {

    companion object {
        /** Opened from Settings to add another playlist: don't auto-connect the saved one. */
        const val EXTRA_ADD = "add"
        /** Reload from the server even if a saved channel list exists (Settings → refresh / switch playlist). */
        const val EXTRA_FORCE = "force"
    }

    private var mode = "xtream"

    private lateinit var tabXtream: Button
    private lateinit var tabM3u: Button
    private lateinit var xtreamForm: View
    private lateinit var m3uForm: View
    private lateinit var server: EditText
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var m3uUrl: EditText
    private lateinit var name: EditText
    private lateinit var error: TextView
    private lateinit var progress: ProgressBar
    private lateinit var login: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val saved = if (intent.getBooleanExtra(EXTRA_ADD, false)) null else Prefs.load(this)
        if (saved == null) { showForm(null); return }

        val fmt = Settings.liveFormat(this)
        // Instant start: open Home with the saved channel list, refresh it quietly
        if (!intent.getBooleanExtra(EXTRA_FORCE, false) && Repository.restore(this, saved, fmt)) {
            Repository.refreshInBackground(this, saved, fmt)
            goHome()
            return
        }
        // First start (or refresh): a loading screen, not the form
        setContentView(R.layout.activity_splash)
        lifecycleScope.launch {
            try {
                Repository.load(saved, fmt)
                Repository.persist(applicationContext, fmt)
                goHome()
            } catch (e: Exception) {
                showForm(saved)
                showError("تعذر الاتصال: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun goHome() {
        startActivity(Intent(this, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
        overridePendingTransition(0, 0)
        finish()
    }

    private fun showForm(prefill: Playlist?) {
        setContentView(R.layout.activity_login)
        tabXtream = findViewById(R.id.tabXtream)
        tabM3u = findViewById(R.id.tabM3u)
        xtreamForm = findViewById(R.id.xtreamForm)
        m3uForm = findViewById(R.id.m3uForm)
        server = findViewById(R.id.server)
        username = findViewById(R.id.username)
        password = findViewById(R.id.password)
        m3uUrl = findViewById(R.id.m3uUrl)
        name = findViewById(R.id.name)
        error = findViewById(R.id.error)
        progress = findViewById(R.id.progress)
        login = findViewById(R.id.login)

        tabXtream.setOnClickListener { setMode("xtream") }
        tabM3u.setOnClickListener { setMode("m3u") }
        login.setOnClickListener { submit() }
        if (prefill != null) fill(prefill)
        server.requestFocus()
    }

    private fun setMode(m: String) {
        mode = m
        xtreamForm.visibility = if (m == "xtream") View.VISIBLE else View.GONE
        m3uForm.visibility = if (m == "m3u") View.VISIBLE else View.GONE
        tabXtream.setBackgroundResource(if (m == "xtream") R.drawable.bg_button_primary else R.drawable.bg_button)
        tabM3u.setBackgroundResource(if (m == "m3u") R.drawable.bg_button_primary else R.drawable.bg_button)
        tabXtream.setTextColor(getColor(if (m == "xtream") R.color.bg else R.color.text))
        tabM3u.setTextColor(getColor(if (m == "m3u") R.color.bg else R.color.text))
    }

    private fun fill(p: Playlist) {
        setMode(p.type)
        server.setText(p.server)
        username.setText(p.username)
        password.setText(p.password)
        m3uUrl.setText(p.m3uUrl)
        name.setText(p.name)
    }

    private fun submit() {
        val p = if (mode == "xtream") {
            if (server.text.isBlank() || username.text.isBlank() || password.text.isBlank()) {
                showError("أدخل رابط السيرفر واسم المستخدم وكلمة المرور"); return
            }
            Playlist("xtream", name.text.toString().trim(), server.text.toString().trim(),
                username.text.toString().trim(), password.text.toString().trim())
        } else {
            if (m3uUrl.text.isBlank()) { showError("أدخل رابط M3U"); return }
            Playlist("m3u", name.text.toString().trim(), m3uUrl = m3uUrl.text.toString().trim())
        }
        connect(p)
    }

    private fun connect(p: Playlist) {
        error.visibility = View.GONE
        progress.visibility = View.VISIBLE
        login.isEnabled = false
        lifecycleScope.launch {
            try {
                val fmt = Settings.liveFormat(this@LoginActivity)
                Repository.load(p, fmt)
                Prefs.save(this@LoginActivity, p)
                Repository.persist(applicationContext, fmt)
                goHome()
            } catch (e: Exception) {
                showError("تعذر الاتصال: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                progress.visibility = View.GONE
                login.isEnabled = true
            }
        }
    }

    private fun showError(msg: String) {
        error.text = msg
        error.visibility = View.VISIBLE
    }
}
