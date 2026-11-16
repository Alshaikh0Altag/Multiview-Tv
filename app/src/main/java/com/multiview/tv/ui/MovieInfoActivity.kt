package com.multiview.tv.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import coil.load
import coil.transform.RoundedCornersTransformation
import com.multiview.tv.R
import com.multiview.tv.data.Library
import com.multiview.tv.data.Movie
import com.multiview.tv.data.MovieDetails
import com.multiview.tv.data.Prefs
import com.multiview.tv.data.Repository
import kotlinx.coroutines.launch

/** Movie details shown before playing: poster, plot, cast, rating; play / resume / favorite. */
class MovieInfoActivity : BaseActivity() {

    companion object {
        private const val E_ID = "id"; private const val E_NAME = "name"; private const val E_POSTER = "poster"
        private const val E_CAT = "cat"; private const val E_URL = "url"; private const val E_RATING = "rating"
        private const val E_ADDED = "added"

        fun intent(ctx: Context, m: Movie): Intent = Intent(ctx, MovieInfoActivity::class.java)
            .putExtra(E_ID, m.id).putExtra(E_NAME, m.name).putExtra(E_POSTER, m.poster)
            .putExtra(E_CAT, m.categoryId).putExtra(E_URL, m.url).putExtra(E_RATING, m.rating)
            .putExtra(E_ADDED, m.added)
    }

    private lateinit var movie: Movie
    private lateinit var btnPlay: Button
    private lateinit var btnStartOver: Button
    private lateinit var btnFav: Button
    private var poster: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_movie_info)
        movie = Movie(
            id = intent.getStringExtra(E_ID) ?: run { finish(); return },
            name = intent.getStringExtra(E_NAME) ?: "",
            poster = intent.getStringExtra(E_POSTER),
            categoryId = intent.getStringExtra(E_CAT) ?: "",
            url = intent.getStringExtra(E_URL) ?: "",
            rating = intent.getStringExtra(E_RATING),
            added = intent.getLongExtra(E_ADDED, 0),
        )
        poster = movie.poster

        findViewById<TextView>(R.id.infoTitle).text = movie.name
        showRating(movie.rating)
        loadPoster(movie.poster)

        btnPlay = findViewById(R.id.btnPlay)
        btnStartOver = findViewById(R.id.btnStartOver)
        btnFav = findViewById(R.id.btnFav)
        btnPlay.setOnClickListener { play(VodPlayerActivity.RESUME_YES) }
        btnStartOver.setOnClickListener { play(VodPlayerActivity.RESUME_NO) }
        btnFav.setOnClickListener { Library.toggleFavoriteMovie(this, movie); updateFav() }
        btnPlay.requestFocus()

        lifecycleScope.launch {
            try {
                showDetails(Repository.movieInfo(movie.id))
            } catch (_: Exception) {
                // details are optional — the movie still plays
            } finally {
                findViewById<ProgressBar>(R.id.infoLoading).visibility = View.GONE
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateButtons()
        updateFav()
    }

    private fun updateButtons() {
        val pos = Prefs.position(this, "m:${movie.id}")
        if (pos > 30_000) {
            btnPlay.text = getString(R.string.resume_from, fmt(pos))
            btnStartOver.visibility = View.VISIBLE
        } else {
            btnPlay.setText(R.string.play)
            btnStartOver.visibility = View.GONE
        }
    }

    private fun updateFav() {
        btnFav.setText(if (Library.isFavoriteMovie(this, movie.id)) R.string.remove_favorite else R.string.add_favorite)
    }

    private fun play(resume: Int) {
        startActivity(VodPlayerActivity.intent(this,
            listOf(VodPlayerActivity.Item("m:${movie.id}", movie.name, movie.url, poster)), 0, resume))
    }

    private fun loadPoster(url: String?) {
        if (url == null) return
        findViewById<ImageView>(R.id.infoPoster).load(url) {
            crossfade(true)
            transformations(RoundedCornersTransformation(20f))
        }
    }

    private fun showRating(r: String?) {
        val v = findViewById<TextView>(R.id.infoRating)
        val value = r?.toDoubleOrNull()?.takeIf { it > 0 } ?: return
        v.text = if (value <= 10) "%.1f / 10".format(value) else r
        v.visibility = View.VISIBLE
    }

    private fun showDetails(d: MovieDetails) {
        if (movie.poster == null && d.poster != null) { poster = d.poster; loadPoster(d.poster) }
        d.backdrop?.let { findViewById<ImageView>(R.id.backdrop).load(it) { crossfade(true) } }
            ?: (d.poster ?: movie.poster)?.let { findViewById<ImageView>(R.id.backdrop).load(it) { crossfade(true) } }
        if (movie.rating == null) showRating(d.rating)
        d.trailer?.let { t ->
            val btn = findViewById<Button>(R.id.btnTrailer)
            btn.visibility = View.VISIBLE
            btn.setOnClickListener { openTrailer(t) }
        }

        val year = d.releaseDate?.take(4)
        findViewById<TextView>(R.id.infoMeta).text =
            listOfNotNull(year, d.duration, d.genre).filter { it.isNotBlank() }.joinToString("  ·  ")
        findViewById<TextView>(R.id.infoPlot).text = d.plot ?: ""
        val people = listOfNotNull(
            d.director?.let { getString(R.string.director, it) },
            d.cast?.let { getString(R.string.cast, it) },
        )
        findViewById<TextView>(R.id.infoPeople).text = people.joinToString("\n")
    }

    /** Opens the trailer in the YouTube app (or any app that can open the link). */
    private fun openTrailer(t: String) {
        val id = when {
            t.contains("v=") -> t.substringAfter("v=").substringBefore('&')
            t.contains("youtu.be/") -> t.substringAfter("youtu.be/").substringBefore('?')
            else -> t.trim()
        }
        val app = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("vnd.youtube:$id"))
        val web = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/watch?v=$id"))
        try { startActivity(app) } catch (_: android.content.ActivityNotFoundException) {
            try { startActivity(web) } catch (_: android.content.ActivityNotFoundException) {
                android.widget.Toast.makeText(this, R.string.no_youtube, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
        else "%d:%02d".format(s / 60, s % 60)
    }
}
