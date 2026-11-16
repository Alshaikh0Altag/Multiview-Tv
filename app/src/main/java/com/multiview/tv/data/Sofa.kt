package com.multiview.tv.data

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Football and tennis from Sofascore: Arabic team names, live minute, scorers, cards, stats,
 * league table, and the TV channels that carry the match in Saudi Arabia.
 */
object Sofa {
    const val TV_COUNTRY = "SA"

    /** Which Sofascore competitions belong to our league codes (matched by country + competition name). */
    private val rules: Map<String, (String, String) -> Boolean> = mapOf(
        "ksa.1" to { c, t -> c == "saudi arabia" && ("pro league" in t || "roshn" in t) },
        "ksa.kings.cup" to { c, t -> c == "saudi arabia" && ("king" in t || "cup" in t) },
        "egy.1" to { c, t -> c == "egypt" && "premier" in t },
        "uae.1" to { c, t -> (c == "united arab emirates" || c == "uae") && ("pro league" in t || "league" in t) },
        "qat.1" to { c, t -> c == "qatar" && ("stars" in t || "league" in t) },
        "mar.1" to { c, t -> c == "morocco" && ("botola" in t || "league" in t) },
        "tun.1" to { c, t -> c == "tunisia" && ("ligue" in t || "league" in t) },
        "alg.1" to { c, t -> c == "algeria" && ("ligue" in t || "league" in t) },
        "afc.champions" to { _, t -> "afc champions league" in t && "two" !in t && " 2" !in t },
        "afc.cup" to { _, t -> "afc champions league two" in t || "afc champions league 2" in t || t == "afc cup" },
        "afc.asian.cup" to { _, t -> "asian cup" in t },
        "caf.champions" to { _, t -> "caf champions league" in t },
        "caf.nations" to { _, t -> "africa cup of nations" in t },
        "uefa.champions" to { _, t -> t == "uefa champions league" || t.startsWith("uefa champions league,") },
        "uefa.europa" to { _, t -> t == "uefa europa league" || t.startsWith("uefa europa league,") },
        "uefa.europa.conf" to { _, t -> "conference league" in t },
        "uefa.super_cup" to { _, t -> t == "uefa super cup" },
        "uefa.nations" to { _, t -> "nations league" in t && "uefa" in t },
        "uefa.euro" to { _, t -> t.startsWith("euro") && "qualification" !in t },
        "eng.1" to { c, t -> c == "england" && t == "premier league" },
        "eng.2" to { c, t -> c == "england" && t == "championship" },
        "eng.fa" to { c, t -> c == "england" && "fa cup" in t },
        "eng.league_cup" to { c, t -> c == "england" && ("efl cup" in t || "carabao" in t || "league cup" in t) },
        "esp.1" to { c, t -> c == "spain" && "laliga" in t.replace(" ", "") && "2" !in t },
        "esp.copa_del_rey" to { c, t -> c == "spain" && "copa del rey" in t },
        "esp.super_cup" to { c, t -> c == "spain" && "supercopa" in t },
        "ita.1" to { c, t -> c == "italy" && t == "serie a" },
        "ita.coppa_italia" to { c, t -> c == "italy" && "coppa italia" in t },
        "ger.1" to { c, t -> c == "germany" && t == "bundesliga" },
        "ger.dfb_pokal" to { c, t -> c == "germany" && "dfb" in t },
        "fra.1" to { c, t -> c == "france" && t == "ligue 1" },
        "fra.coupe_de_france" to { c, t -> c == "france" && "coupe de france" in t },
        "ned.1" to { c, t -> c == "netherlands" && "eredivisie" in t },
        "por.1" to { c, t -> c == "portugal" && ("liga portugal" in t || "primeira" in t) && "2" !in t },
        "tur.1" to { c, t -> (c == "türkiye" || c == "turkey") && ("süper lig" in t || "super lig" in t) },
        "sco.1" to { c, t -> c == "scotland" && "premiership" in t },
        "bel.1" to { c, t -> c == "belgium" && ("pro league" in t || "first division" in t) },
        "fifa.world" to { _, t -> t == "world cup" || t == "fifa world cup" },
        "fifa.cwc" to { _, t -> "club world cup" in t },
        "fifa.worldq.afc" to { _, t -> "world cup qual" in t && ("afc" in t || "asia" in t) },
        "fifa.worldq.caf" to { _, t -> "world cup qual" in t && ("caf" in t || "africa" in t) },
        "fifa.worldq.uefa" to { _, t -> "world cup qual" in t && ("uefa" in t || "europe" in t) },
        "fifa.worldq.conmebol" to { _, t -> "world cup qual" in t && ("conmebol" in t || "south america" in t) },
        "fifa.friendly" to { _, t -> "int. friendly" in t || "international friendly" in t },
        "club.friendly" to { _, t -> "club friendly" in t },
        "conmebol.libertadores" to { _, t -> "libertadores" in t },
        "conmebol.america" to { _, t -> "copa américa" in t || "copa america" in t },
        "usa.1" to { c, t -> c == "usa" && t == "mls" },
        "bra.1" to { c, t -> c == "brazil" && ("brasileir" in t || "serie a" in t) },
        "arg.1" to { c, t -> c == "argentina" && ("liga profesional" in t || "primera" in t) },
    )

    private fun codeFor(category: String, tournament: String): String? {
        val c = category.lowercase(Locale.ROOT)
        val t = tournament.lowercase(Locale.ROOT)
        return rules.entries.firstOrNull { it.value(c, t) }?.key
    }

    // ---------- day schedule ----------

    /** Today's matches (football in the chosen leagues; tennis ATP/WTA singles when [tennis]). */
    suspend fun today(leagues: Set<String>, tennis: Boolean): List<Match> = coroutineScope {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val dayStart = cal.timeInMillis
        val dayEnd = dayStart + 24 * 3600_000L
        // Sofascore days are UTC: the local day can touch two of them
        val utc = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val dates = setOf(utc.format(Date(dayStart)), utc.format(Date(dayEnd - 1)))

        val football = dates.map { d -> async { parseDay(SofaHttp.get("/api/v1/sport/football/scheduled-events/$d"), "football", leagues) } }
        val tennisJobs = if (tennis) dates.map { d ->
            async { runCatching { parseDay(SofaHttp.get("/api/v1/sport/tennis/scheduled-events/$d"), "tennis", leagues) }.getOrDefault(emptyList()) }
        } else emptyList()

        (football.flatMap { it.await() } + tennisJobs.flatMap { it.await() })
            .distinctBy { it.id }
            .filter { it.start in dayStart until dayEnd || it.isLive }
            .sortedWith(compareBy<Match>({ if (it.isLive) 0 else if (it.isFinished) 2 else 1 }, { it.start }))
    }

    private fun arabic(o: JSONObject?, key: String): String? =
        o?.optJSONObject("fieldTranslations")?.optJSONObject(key)?.optString("ar")?.takeIf { it.isNotBlank() }

    private fun parseDay(body: String, sport: String, leagues: Set<String>): List<Match> {
        val events = JSONObject(body).optJSONArray("events") ?: return emptyList()
        val out = ArrayList<Match>()
        for (i in 0 until events.length()) {
            val e = events.getJSONObject(i)
            val tour = e.optJSONObject("tournament") ?: continue
            val ut = tour.optJSONObject("uniqueTournament")
            val catName = tour.optJSONObject("category")?.optString("name") ?: ""
            val tourName = ut?.optString("name") ?: tour.optString("name")
            val code: String
            if (sport == "football") {
                code = codeFor(catName, tourName) ?: continue
                if (code !in leagues) continue
            } else {
                // tennis: ATP / WTA singles and the big team events only
                val cat = catName.lowercase(Locale.ROOT)
                if (cat !in setOf("atp", "wta") && "davis" !in tourName.lowercase() && "united cup" !in tourName.lowercase()) continue
                if ("doubles" in tour.optString("name").lowercase()) continue
                if (e.optJSONObject("homeTeam")?.optString("type") == "2") continue   // a doubles pair
                code = "tennis." + cat
            }
            out += toMatch(e, sport, code, if (sport == "tennis") "$catName · $tourName" else (arabicLeague(code) ?: tourName))
        }
        return out
    }

    private fun arabicLeague(code: String) = Settings.ALL_LEAGUES[code]

    private fun team(o: JSONObject?, score: JSONObject?, sport: String): Team {
        val id = o?.optInt("id")?.takeIf { it > 0 }?.toString()
        val name = arabic(o, "nameTranslation") ?: o?.optString("name") ?: ""
        val short = arabic(o, "shortNameTranslation") ?: o?.optString("shortName")?.ifBlank { null } ?: name
        val sc = score?.let { s ->
            if (sport == "tennis") s.opt("display")?.toString() ?: s.opt("current")?.toString()
            else s.opt("current")?.toString()
        }?.takeIf { it != "null" && it.isNotBlank() }
        return Team(name, short, id?.let { "https://api.sofascore.app/api/v1/team/$it/image" }, sc, id)
    }

    private fun toMatch(e: JSONObject, sport: String, code: String, leagueName: String): Match {
        val status = e.optJSONObject("status")
        val type = status?.optString("type") ?: "notstarted"
        val state = when (type) {
            "inprogress" -> "in"
            "finished" -> "post"
            else -> if (type == "canceled" || type == "postponed") "post" else "pre"
        }
        val hs = e.optJSONObject("homeScore")
        val aws = e.optJSONObject("awayScore")
        val sets = ArrayList<Pair<String, String>>()
        if (sport == "tennis") for (p in 1..5) {
            val h = hs?.opt("period$p")?.toString(); val a = aws?.opt("period$p")?.toString()
            if (h.isNullOrBlank() || a.isNullOrBlank() || h == "null") break
            sets += h to a
        }
        return Match(
            id = e.optInt("id").toString(),
            league = leagueName,
            leagueCode = code,
            start = e.optLong("startTimestamp") * 1000,
            state = state,
            statusText = liveText(e, sport),
            home = team(e.optJSONObject("homeTeam"), hs, sport),
            away = team(e.optJSONObject("awayTeam"), aws, sport),
            broadcasts = emptyList(),
            sport = sport,
            source = "sofa",
            tournamentId = e.optJSONObject("tournament")?.optJSONObject("uniqueTournament")?.optInt("id") ?: 0,
            seasonId = e.optJSONObject("season")?.optInt("id") ?: 0,
            sets = sets,
        )
    }

    /** "23'", "استراحة", "الشوط الثاني"… */
    private fun liveText(e: JSONObject, sport: String): String {
        val st = e.optJSONObject("status") ?: return ""
        val code = st.optInt("code")
        if (st.optString("type") == "canceled") return "أُلغيت"
        if (st.optString("type") == "postponed") return "أُجّلت"
        if (sport == "tennis") return st.optString("description")
        val t = e.optJSONObject("time")
        val periodStart = t?.optLong("currentPeriodStartTimestamp") ?: 0
        val now = System.currentTimeMillis() / 1000
        return when (code) {
            6 -> if (periodStart > 0) "${((now - periodStart) / 60 + 1).coerceIn(1, 45)}'" else "الشوط الأول"
            7 -> if (periodStart > 0) "${(45 + (now - periodStart) / 60 + 1).coerceIn(46, 90)}'" else "الشوط الثاني"
            31 -> "استراحة"
            41, 42 -> "أشواط إضافية"
            50 -> "ركلات الترجيح"
            else -> st.optString("description")
        }
    }

    // ---------- match centre ----------

    suspend fun details(m: Match): MatchDetails = coroutineScope {
        val ev = async { runCatching { JSONObject(SofaHttp.get("/api/v1/event/${m.id}")).optJSONObject("event") }.getOrNull() }
        val inc = async { runCatching { JSONObject(SofaHttp.get("/api/v1/event/${m.id}/incidents")) }.getOrNull() }
        val st = async { runCatching { JSONObject(SofaHttp.get("/api/v1/event/${m.id}/statistics")) }.getOrNull() }
        val tv = async { runCatching { tvChannels(m.id) }.getOrDefault(emptyList()) }
        val table = async {
            if (m.sport == "football" && m.tournamentId > 0 && m.seasonId > 0) runCatching { standings(m) }.getOrNull() else null
        }

        val e = ev.await()
        val match = e?.let { toMatch(it, m.sport, m.leagueCode, m.league) } ?: m
        val venue = e?.optJSONObject("venue")?.let { v ->
            val stadium = v.optJSONObject("stadium")?.optString("name")?.ifBlank { null } ?: v.optString("name").ifBlank { null }
            val city = v.optJSONObject("city")?.optString("name")?.ifBlank { null }
            listOfNotNull(stadium, city).joinToString(" — ").ifBlank { null }
        }
        val referee = e?.optJSONObject("referee")?.optString("name")?.ifBlank { null }

        val events = ArrayList<MatchEvent>()
        inc.await()?.optJSONArray("incidents")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val cls = o.optString("incidentClass").lowercase(Locale.ROOT)
                val kind = when (o.optString("incidentType")) {
                    "goal" -> when (cls) { "owngoal" -> "own_goal"; "penalty" -> "penalty"; else -> "goal" }
                    "card" -> if (cls == "yellow") "yellow" else "red"
                    else -> continue
                }
                val added = o.optInt("addedTime", 0).takeIf { it > 0 }?.let { "+$it" } ?: ""
                val player = o.optJSONObject("player")?.optString("name") ?: o.optString("playerName")
                events += MatchEvent(kind, "${o.optInt("time")}$added'", player, o.optBoolean("isHome", true))
            }
        }
        events.reverse()   // Sofascore lists the latest first

        val wanted = linkedMapOf(
            "ballPossession" to "الاستحواذ", "expectedGoals" to "الأهداف المتوقعة (xG)",
            "totalShotsOnGoal" to "التسديدات", "shotsOnGoal" to "على المرمى", "cornerKicks" to "الركنيات",
            "fouls" to "الأخطاء", "offsides" to "التسلل", "goalkeeperSaves" to "التصديات", "yellowCards" to "البطاقات الصفراء",
        )
        val stats = ArrayList<MatchStat>()
        st.await()?.optJSONArray("statistics")?.let { periods ->
            val all = (0 until periods.length()).map { periods.getJSONObject(it) }.firstOrNull { it.optString("period") == "ALL" }
            val found = HashMap<String, MatchStat>()
            all?.optJSONArray("groups")?.let { groups ->
                for (g in 0 until groups.length()) {
                    val items = groups.getJSONObject(g).optJSONArray("statisticsItems") ?: continue
                    for (k in 0 until items.length()) {
                        val item = items.getJSONObject(k)
                        val key = item.optString("key")
                        val label = wanted[key] ?: continue
                        found[key] = MatchStat(label, item.optString("home"), item.optString("away"))
                    }
                }
            }
            wanted.keys.forEach { k -> found[k]?.let { stats += it } }
        }

        val (title, rows) = table.await() ?: (null to emptyList())
        MatchDetails(match, venue, events, stats, tv.await(), referee, rows, title)
    }

    /** Short report of the last broadcaster lookup, shown on the match page when nothing is found. */
    @Volatile var lastTvDiag: String = ""

    /** Countries that share the same Arabic broadcasters (beIN MENA, etc.), in order of preference. */
    private val mena = listOf("SA", "AE", "QA", "KW", "BH", "OM", "JO", "EG", "IQ", "LB", "MA", "DZ", "TN")

    /** Channel names that carry the event in Saudi Arabia (or the nearest Arab country that has data). */
    private suspend fun tvChannels(eventId: String): List<String> {
        val body = try {
            SofaHttp.get("/api/v1/tv/event/$eventId/country-channels")
        } catch (e: SofaHttp.HttpError) {
            lastTvDiag = "tv: HTTP ${e.code}"; return emptyList()
        }
        val cc = JSONObject(body).optJSONObject("countryChannels")
        if (cc == null || cc.length() == 0) { lastTvDiag = "tv: no countries"; return emptyList() }
        val country = mena.firstOrNull { (cc.optJSONArray(it)?.length() ?: 0) > 0 }
        if (country == null) {
            lastTvDiag = "tv: ${cc.length()} countries, none Arab (" + cc.keys().asSequence().take(8).joinToString(",") + ")"
            return emptyList()
        }
        val arr = cc.getJSONArray(country)
        val names = LinkedHashMap<String, String>()
        val wanted = LinkedHashSet<String>()
        for (i in 0 until arr.length()) {
            when (val v = arr.get(i)) {
                is JSONObject -> {          // some versions send the channel itself
                    val id = v.opt("id")?.toString() ?: continue
                    wanted += id
                    v.optString("name").takeIf { it.isNotBlank() }?.let { names[id] = it }
                }
                else -> wanted += v.toString()
            }
        }
        // names for plain ids: try the lookups the website uses, keep whatever answers
        val tried = ArrayList<String>()
        for (path in listOf(
            "/api/v1/tv/country/$country/channels",
            "/api/v1/tv/channels/$country",
        )) {
            if (names.size >= wanted.size) break
            tried += path.substringAfter("/tv/") + ":" + runCatching { collectNames(JSONTokenerAny(SofaHttp.get(path)), wanted, names); "ok" }
                .getOrElse { (it as? SofaHttp.HttpError)?.code?.toString() ?: "err" }
        }
        for (id in wanted - names.keys) {
            for (path in listOf("/api/v1/tv/channel/$id/event/$eventId/votes", "/api/v1/tv/channel/$id/schedule", "/api/v1/tv/channel/$id")) {
                if (id in names) break
                runCatching { collectNames(JSONTokenerAny(SofaHttp.get(path)), setOf(id), names) }
            }
        }
        lastTvDiag = "tv $country: ${wanted.size} ids, ${names.size} names · " + tried.joinToString(" ")
        return wanted.mapNotNull { names[it] }
    }

    private fun JSONTokenerAny(s: String): Any = org.json.JSONTokener(s).nextValue()

    /** Walks any JSON and keeps {id, name} objects whose id is one we want. */
    private fun collectNames(node: Any?, wanted: Set<String>, out: MutableMap<String, String>) {
        when (node) {
            is JSONObject -> {
                val id = node.opt("id")?.toString()
                val name = node.optString("name")
                if (id != null && id in wanted && name.isNotBlank()) out[id] = name
                node.keys().forEach { collectNames(node.opt(it), wanted, out) }
            }
            is JSONArray -> for (i in 0 until node.length()) collectNames(node.opt(i), wanted, out)
        }
    }

    private suspend fun standings(m: Match): Pair<String?, List<StandingRow>> {
        val json = JSONObject(SofaHttp.get("/api/v1/unique-tournament/${m.tournamentId}/season/${m.seasonId}/standings/total"))
        val tables = json.optJSONArray("standings") ?: return null to emptyList()
        val ids = setOfNotNull(m.home.id, m.away.id)
        for (i in 0 until tables.length()) {
            val t = tables.getJSONObject(i)
            val rows = t.optJSONArray("rows") ?: continue
            val all = (0 until rows.length()).map { rows.getJSONObject(it) }
            val mine = all.filter { it.optJSONObject("team")?.optInt("id")?.toString() in ids }
            if (mine.isEmpty()) continue
            return t.optString("name").ifBlank { null } to mine.map { r ->
                val team = r.optJSONObject("team")
                val gf = r.optInt("scoresFor"); val ga = r.optInt("scoresAgainst")
                StandingRow(
                    position = r.optInt("position"),
                    team = arabic(team, "nameTranslation") ?: team?.optString("name") ?: "",
                    teamId = team?.optInt("id")?.toString(),
                    played = r.optInt("matches"),
                    goalDiff = (gf - ga).let { if (it > 0) "+$it" else "$it" },
                    points = r.optInt("points"),
                )
            }.sortedBy { it.position }
        }
        return null to emptyList()
    }
}
