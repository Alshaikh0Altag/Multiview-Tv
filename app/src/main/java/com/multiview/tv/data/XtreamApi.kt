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
        val arr = array("get_live_streams")
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val id = o.optString("stream_id")
            Channel(
                id = id,
                number = o.optInt("num", it + 1),
                name = o.optString("name"),
                logo = o.str("stream_icon"),
                categoryId = o.optString("category_id"),
                url = "$base/live/$user/$pass/$id.$liveExt",
            )
        }
    }

    // ---------- movies ----------

    fun vodCategories() = categories("get_vod_categories")

    fun vodStreams(): List<Movie> {
        val arr = array("get_vod_streams", timeout = 45_000)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val id = o.optString("stream_id")
            val ext = o.str("container_extension") ?: "mp4"
            Movie(
                id = id,
                name = o.optString("name"),
                poster = o.str("stream_icon"),
                categoryId = o.optString("category_id"),
                url = "$base/movie/$user/$pass/$id.$ext",
                rating = o.str("rating"),
            )
        }
    }

    // ---------- series ----------

    fun seriesCategories() = categories("get_series_categories")

    fun seriesList(): List<Series> {
        val arr = array("get_series", timeout = 45_000)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Series(
                id = o.optString("series_id"),
                name = o.optString("name"),
                cover = o.str("cover"),
                categoryId = o.optString("category_id"),
                plot = o.str("plot"),
                rating = o.str("rating"),
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
