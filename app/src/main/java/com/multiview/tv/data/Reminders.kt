package com.multiview.tv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Matches the user asked to be reminded of (5 minutes before kick-off, while the app is open). */
object Reminders {
    const val LEAD_MS = 5 * 60_000L
    private fun sp(c: Context) = c.getSharedPreferences("reminders", Context.MODE_PRIVATE)

    private fun toJson(m: Match) = JSONObject()
        .put("id", m.id).put("league", m.league).put("code", m.leagueCode).put("start", m.start)
        .put("sport", m.sport).put("event", m.eventId).put("draw", m.draw).put("round", m.round)
        .put("h", m.home.name).put("hs", m.home.shortName).put("hl", m.home.logo ?: "").put("hid", m.home.id ?: "")
        .put("a", m.away.name).put("as", m.away.shortName).put("al", m.away.logo ?: "").put("aid", m.away.id ?: "")

    private fun fromJson(o: JSONObject) = Match(
        id = o.optString("id"), league = o.optString("league"), leagueCode = o.optString("code"),
        start = o.optLong("start"), state = "pre", statusText = "",
        home = Team(o.optString("h"), o.optString("hs"), o.optString("hl").ifBlank { null }, null, o.optString("hid").ifBlank { null }),
        away = Team(o.optString("a"), o.optString("as"), o.optString("al").ifBlank { null }, null, o.optString("aid").ifBlank { null }),
        broadcasts = emptyList(), sport = o.optString("sport", "football"),
        eventId = o.optString("event"), draw = o.optString("draw"), round = o.optString("round"),
    )

    fun all(c: Context): List<Match> {
        val arr = runCatching { JSONArray(sp(c).getString("list", "[]")) }.getOrDefault(JSONArray())
        return (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
    }

    private fun save(c: Context, list: List<Match>) {
        val arr = JSONArray(); list.forEach { arr.put(toJson(it)) }
        sp(c).edit().putString("list", arr.toString()).apply()
    }

    fun has(c: Context, id: String) = all(c).any { it.id == id }

    fun toggle(c: Context, m: Match): Boolean {
        val list = all(c)
        val on = list.none { it.id == m.id }
        save(c, if (on) list + m else list.filter { it.id != m.id })
        return on
    }

    /** Reminders that are due now (removed from the list), dropping ones long past. */
    fun takeDue(c: Context, now: Long = System.currentTimeMillis()): List<Match> {
        val list = all(c)
        if (list.isEmpty()) return emptyList()
        val due = list.filter { now >= it.start - LEAD_MS && now < it.start + 15 * 60_000L }
        val keep = list.filter { now < it.start - LEAD_MS }
        if (keep.size != list.size) save(c, keep)
        return due
    }
}
