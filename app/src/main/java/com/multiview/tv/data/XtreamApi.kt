package com.multiview.tv.data

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Xtream Codes "player_api.php" client: live, movies (VOD) and series.
 * server: e.g. http://host:8080 (no trailing slash needed)
 */
class XtreamApi(
    server: String,
    private val user: String,
    private val pass: String,
    /** live stream container: "ts" or "m3u8" */
    private val liveExt: String = "ts",
) {

    private val base = server.trim().trimEnd('/').let {
        if (it.startsWith("http://") || it.startsWith("https://")) it else "http://$it"
    }

    private fun api(action: String? = null, extra: Map<String, String> = emptyMap()): String {
        val b = Uri.parse("$base/player_api.php").buildUpon()
            .appendQueryParameter("username", user)
            .appendQueryParameter("password", pass)
        if (action != null) b.appendQueryParameter("action", action)
        extra.forEach { (k, v) -> b.appendQueryParameter(k, v) }
        return b.build().toString()
    }

    private fun array(action: String, extra: Map<String, String> = emptyMap(), timeout: Int = 30_000): JSONArray {
        val body = Http.get(api(action, extra), timeoutMs = timeout).trim()
        // Some panels return {} or "" instead of [] when empty
        return if (body.startsWith("[")) JSONArray(body) else JSONArray()
    }

    private fun categories(action: String): List<Category> {
        val arr = array(action)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Category(o.optString("category_id"), o.optString("category_name"))
        }
    }

    private fun JSONObject.str(key: String): String? =
        optString(key, "").takeIf { it.isNotBlank() && it != "null" }

    // ---------- account ----------

    fun accountInfo(): AccountInfo {
        val json = JSONObject(Http.get(api()))
        val ui = json.optJSONObject("user_info") ?: throw IllegalStateException("رد غير صالح من السيرفر")
        val auth = ui.optInt("auth", 0) == 1
        val status = ui.optString("status", "")
        val max = ui.optString("max_connections", "1").toIntOrNull() ?: 1
        val exp = ui.optString("exp_date", "").toLongOrNull()?.times(1000)
        return AccountInfo(active = auth && status.equals("Active", true), maxConnections = max, expiresAt = exp)
    }

    // ---------- live ----------

    fun liveCategories() = categories("get_live_categories")

    fun liveStreams(): List<Channel> {
        var n = 0
        return Http.streamArray(api("get_live_streams"), timeoutMs = 45_000) { o ->
            val id = o["stream_id"] ?: return@streamArray null
            n++
            Channel(
                id = id,
                number = o["num"]?.toIntOrNull() ?: n,
                name = o["name"] ?: "",
                logo = o["stream_icon"]?.takeIf { it.isNotBlank() && it != "null" },
                categoryId = o["category_id"] ?: "",
                url = "$base/live/$user/$pass/$id.$liveExt",
            )
        }
    }

    // ---------- movies ----------

    fun vodCategories() = categories("get_vod_categories")

    /**
     * Movies of one category, or all movies when [categoryId] is null.
     * Streamed, so even very large libraries don't run the TV box out of memory.
     */
    fun vodStreams(categoryId: String?): List<Movie> {
        val extra = if (categoryId != null) mapOf("category_id" to categoryId) else emptyMap()
        return Http.streamArray(api("get_vod_streams", extra), timeoutMs = 60_000) { o ->
            val id = o["stream_id"] ?: return@streamArray null
            val ext = o["container_extension"]?.takeIf { it.isNotBlank() } ?: "mp4"
            Movie(
                id = id,
                name = o["name"] ?: "",
                poster = o["stream_icon"]?.takeIf { it.isNotBlank() && it != "null" },
                categoryId = o["category_id"] ?: "",
                url = "$base/movie/$user/$pass/$id.$ext",
                rating = o["rating"]?.takeIf { it.isNotBlank() && it != "0" && it != "null" },
                added = o["added"]?.toLongOrNull() ?: 0,
            )
        }
    }

    fun vodInfo(movieId: String): MovieDetails {
        val json = JSONObject(Http.get(api("get_vod_info", mapOf("vod_id" to movieId)), timeoutMs = 20_000))
        val i = json.optJSONObject("info") ?: JSONObject()
        val backdrop = i.optJSONArray("backdrop_path")?.optString(0)?.takeIf { it.isNotBlank() }
        return MovieDetails(
            plot = i.str("plot") ?: i.str("description"),
            genre = i.str("genre"),
            director = i.str("director"),
            cast = i.str("cast") ?: i.str("actors"),
            releaseDate = i.str("releasedate") ?: i.str("release_date"),
            duration = i.str("duration"),
            rating = i.str("rating")?.takeIf { it != "0" },
            backdrop = backdrop,
            poster = i.str("movie_image") ?: i.str("cover_big"),
        )
    }

    // ---------- series ----------

    fun seriesCategories() = categories("get_series_categories")

    fun seriesList(categoryId: String?): List<Series> {
        val extra = if (categoryId != null) mapOf("category_id" to categoryId) else emptyMap()
        return Http.streamArray(api("get_series", extra), timeoutMs = 60_000) { o ->
            val id = o["series_id"] ?: return@streamArray null
            Series(
                id = id,
                name = o["name"] ?: "",
                cover = o["cover"]?.takeIf { it.isNotBlank() && it != "null" },
                categoryId = o["category_id"] ?: "",
                plot = o["plot"]?.takeIf { it.isNotBlank() },
                rating = o["rating"]?.takeIf { it.isNotBlank() && it != "0" && it != "null" },
                added = o["last_modified"]?.toLongOrNull() ?: 0,
                genre = o["genre"]?.takeIf { it.isNotBlank() },
                releaseDate = o["releaseDate"]?.takeIf { it.isNotBlank() },
            )
        }
    }

    fun seriesInfo(s: Series): SeriesDetails {
        val json = JSONObject(Http.get(api("get_series_info", mapOf("series_id" to s.id)), timeoutMs = 30_000))
        val info = json.optJSONObject("info")
        val seasons = sortedMapOf<Int, List<Episode>>()

        // "episodes" is normally an object {"1":[...],"2":[...]}, on some panels an array of arrays
        val epsObj = json.optJSONObject("episodes")
        val epsArr = json.optJSONArray("episodes")
        val groups = mutableListOf<Pair<Int, JSONArray>>()
        if (epsObj != null) {
            epsObj.keys().forEach { k -> epsObj.optJSONArray(k)?.let { groups += (k.toIntOrNull() ?: 0) to it } }
        } else if (epsArr != null) {
            for (i in 0 until epsArr.length()) epsArr.optJSONArray(i)?.let { groups += (i + 1) to it }
        }

        groups.forEach { (seasonKey, arr) ->
            val list = (0 until arr.length()).map { i ->
                val e = arr.getJSONObject(i)
                val id = e.optString("id")
                val ext = e.str("container_extension") ?: "mp4"
                val einfo = e.optJSONObject("info")
                Episode(
                    id = id,
                    season = e.optInt("season", seasonKey),
                    number = e.optInt("episode_num", i + 1),
                    title = e.str("title") ?: "الحلقة ${i + 1}",
                    url = "$base/series/$user/$pass/$id.$ext",
                    plot = einfo?.str("plot"),
                    duration = einfo?.str("duration"),
                )
            }.sortedBy { it.number }
            seasons[seasonKey] = list
        }
        return SeriesDetails(
            series = s,
            plot = info?.str("plot") ?: s.plot,
            cover = info?.str("cover") ?: s.cover,
            seasons = seasons,
        )
    }
}
