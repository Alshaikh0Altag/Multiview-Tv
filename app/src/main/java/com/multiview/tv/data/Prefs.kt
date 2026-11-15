package com.multiview.tv.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Saved playlists (several, one active), favorites, resume positions. */
object Prefs {
    private const val FILE = "multiview_prefs"

    private fun sp(c: Context): SharedPreferences = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---------- playlists ----------

    private fun Playlist.toJson() = JSONObject()
        .put("type", type).put("name", name).put("server", server)
        .put("username", username).put("password", password).put("m3u", m3uUrl)

    private fun JSONObject.toPlaylist() = Playlist(
        type = optString("type", "xtream"),
        name = optString("name"),
        server = optString("server"),
        username = optString("username"),
        password = optString("password"),
        m3uUrl = optString("m3u"),
    )

    fun playlists(c: Context): List<Playlist> {
        val arr = JSONArray(sp(c).getString("playlists", "[]"))
        return (0 until arr.length()).map { arr.getJSONObject(it).toPlaylist() }
    }

    private fun writePlaylists(c: Context, list: List<Playlist>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp(c).edit().putString("playlists", arr.toString()).apply()
    }

    private fun sameLogin(a: Playlist, b: Playlist) =
        a.type == b.type && a.server == b.server && a.username == b.username && a.m3uUrl == b.m3uUrl

    /** Adds (or updates) a playlist and makes it the active one. */
    fun save(c: Context, p: Playlist) {
        val list = playlists(c).toMutableList()
        val i = list.indexOfFirst { sameLogin(it, p) }
        if (i >= 0) list[i] = p else list += p
        writePlaylists(c, list)
        sp(c).edit().putInt("active", list.indexOfFirst { sameLogin(it, p) }).apply()
    }

    fun load(c: Context): Playlist? {
        val list = playlists(c)
        if (list.isEmpty()) return null
        return list.getOrNull(sp(c).getInt("active", 0)) ?: list.first()
    }

    fun activeIndex(c: Context) = sp(c).getInt("active", 0)

    fun setActive(c: Context, index: Int) = sp(c).edit().putInt("active", index).apply()

    fun removePlaylist(c: Context, index: Int) {
        val list = playlists(c).toMutableList()
        if (index !in list.indices) return
        list.removeAt(index)
        writePlaylists(c, list)
        sp(c).edit().putInt("active", 0).apply()
    }

    /** "Log out" = forget the active playlist only. */
    fun clear(c: Context) = removePlaylist(c, activeIndex(c))

    // ---------- favorites ----------

    fun favorites(c: Context): MutableSet<String> =
        sp(c).getStringSet("favorites", emptySet())!!.toMutableSet()

    fun toggleFavorite(c: Context, channelId: String): Boolean {
        val favs = favorites(c)
        val added = if (favs.contains(channelId)) { favs.remove(channelId); false } else { favs.add(channelId); true }
        sp(c).edit().putStringSet("favorites", favs).apply()
        return added
    }

    // ---------- resume positions ----------

    fun savePosition(c: Context, key: String, positionMs: Long, durationMs: Long) {
        val e = c.getSharedPreferences("$FILE.resume", Context.MODE_PRIVATE).edit()
        if (durationMs > 0 && positionMs > durationMs - 120_000) e.remove(key) else e.putLong(key, positionMs)
        e.apply()
    }

    fun position(c: Context, key: String): Long =
        c.getSharedPreferences("$FILE.resume", Context.MODE_PRIVATE).getLong(key, 0L)
}
