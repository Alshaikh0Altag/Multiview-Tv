package com.multiview.tv.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** One entry of "continue watching". */
data class ContinueItem(
    val key: String,          // "m:<id>" for a movie, "e:<id>" for an episode
    val title: String,
    val url: String,
    val poster: String?,
    val seriesId: String?,
    val position: Long,
    val duration: Long,
    val updated: Long,
) {
    val remainingMin: Long get() = if (duration > 0) ((duration - position) / 60_000).coerceAtLeast(1) else 0
}

/** Movie / series favorites and "continue watching", kept on the device. */
object Library {
    private const val FILE = "multiview_library"
    private fun sp(c: Context): SharedPreferences = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---------- favorites ----------

    private fun Movie.toJson() = JSONObject().put("id", id).put("name", name).put("poster", poster ?: "")
        .put("cat", categoryId).put("url", url).put("rating", rating ?: "").put("added", added)

    private fun JSONObject.toMovie() = Movie(
        id = optString("id"), name = optString("name"), poster = optString("poster").ifBlank { null },
        categoryId = optString("cat"), url = optString("url"), rating = optString("rating").ifBlank { null },
        added = optLong("added"),
    )

    private fun Series.toJson() = JSONObject().put("id", id).put("name", name).put("cover", cover ?: "")
        .put("cat", categoryId).put("plot", plot ?: "").put("rating", rating ?: "").put("added", added)
        .put("genre", genre ?: "").put("release", releaseDate ?: "")

    private fun JSONObject.toSeries() = Series(
        id = optString("id"), name = optString("name"), cover = optString("cover").ifBlank { null },
        categoryId = optString("cat"), plot = optString("plot").ifBlank { null },
        rating = optString("rating").ifBlank { null }, added = optLong("added"),
        genre = optString("genre").ifBlank { null }, releaseDate = optString("release").ifBlank { null },
    )

    private fun readObj(c: Context, key: String) = JSONObject(sp(c).getString(key, "{}")!!)
    private fun writeObj(c: Context, key: String, o: JSONObject) = sp(c).edit().putString(key, o.toString()).apply()

    fun favoriteMovies(c: Context): List<Movie> {
        val o = readObj(c, "fav_movies")
        return o.keys().asSequence().map { o.getJSONObject(it).toMovie() }.toList().reversed()
    }

    fun isFavoriteMovie(c: Context, id: String) = readObj(c, "fav_movies").has(id)

    /** @return true when added */
    fun toggleFavoriteMovie(c: Context, m: Movie): Boolean {
        val o = readObj(c, "fav_movies")
        val added = if (o.has(m.id)) { o.remove(m.id); false } else { o.put(m.id, m.toJson()); true }
        writeObj(c, "fav_movies", o)
        return added
    }

    fun favoriteSeries(c: Context): List<Series> {
        val o = readObj(c, "fav_series")
        return o.keys().asSequence().map { o.getJSONObject(it).toSeries() }.toList().reversed()
    }

    fun isFavoriteSeries(c: Context, id: String) = readObj(c, "fav_series").has(id)

    fun toggleFavoriteSeries(c: Context, s: Series): Boolean {
        val o = readObj(c, "fav_series")
        val added = if (o.has(s.id)) { o.remove(s.id); false } else { o.put(s.id, s.toJson()); true }
        writeObj(c, "fav_series", o)
        return added
    }

    // ---------- continue watching ----------

    private fun readContinue(c: Context): MutableList<ContinueItem> {
        val arr = JSONArray(sp(c).getString("continue", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            ContinueItem(
                key = o.optString("key"), title = o.optString("title"), url = o.optString("url"),
                poster = o.optString("poster").ifBlank { null }, seriesId = o.optString("series").ifBlank { null },
                position = o.optLong("pos"), duration = o.optLong("dur"), updated = o.optLong("upd"),
            )
        }.toMutableList()
    }

    private fun writeContinue(c: Context, list: List<ContinueItem>) {
        val arr = JSONArray()
        list.take(60).forEach {
            arr.put(JSONObject().put("key", it.key).put("title", it.title).put("url", it.url)
                .put("poster", it.poster ?: "").put("series", it.seriesId ?: "")
                .put("pos", it.position).put("dur", it.duration).put("upd", it.updated))
        }
        sp(c).edit().putString("continue", arr.toString()).apply()
    }

    /** Called when playback stops. Finished items (last 2 minutes) are removed. */
    fun saveProgress(c: Context, key: String, title: String, url: String, poster: String?, seriesId: String?,
                     position: Long, duration: Long) {
        val list = readContinue(c)
        list.removeAll { it.key == key }
        // For a series keep only the latest episode
        if (seriesId != null) list.removeAll { it.seriesId == seriesId }
        val finished = duration > 0 && position > duration - 120_000
        if (!finished && position > 30_000) {
            list.add(0, ContinueItem(key, title, url, poster, seriesId, position, duration, System.currentTimeMillis()))
        }
        writeContinue(c, list)
    }

    /** "m:" movies or "e:" episodes, newest first. */
    fun continueWatching(c: Context, prefix: String): List<ContinueItem> =
        readContinue(c).filter { it.key.startsWith(prefix) }.sortedByDescending { it.updated }

    fun removeContinue(c: Context, key: String) = writeContinue(c, readContinue(c).filterNot { it.key == key })
}
