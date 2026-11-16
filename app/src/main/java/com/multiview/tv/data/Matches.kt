package com.multiview.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class Team(
    val name: String,
    val shortName: String,
    val logo: String?,
    val score: String?,
    val id: String? = null,
)

data class Match(
    val id: String,
    val league: String,
    val leagueCode: String,
    val start: Long,
    /** "pre" (not started), "in" (live), "post" (finished) */
    val state: String,
    val statusText: String,
    val home: Team,
    val away: Team,
    val broadcasts: List<String>,
    /** "football" or "tennis" */
    val sport: String = "football",
    /** "espn" or "sofa" (Sofascore) */
    val source: String = "espn",
    val tournamentId: Int = 0,
    val seasonId: Int = 0,
    /** tennis: games per set, home to away, e.g. [6 to 4, 3 to 2] */
    val sets: List<Pair<String, String>> = emptyList(),
) {
    val isLive get() = state == "in"
    val isFinished get() = state == "post"
}

data class MatchEvent(
    /** "goal", "own_goal", "penalty", "yellow", "red" */
    val kind: String,
    val minute: String,
    val player: String,
    val home: Boolean,
)

data class MatchStat(val label: String, val home: String, val away: String)

data class StandingRow(
    val position: Int,
    val team: String,
    val teamId: String?,
    val played: Int,
    val goalDiff: String,
    val points: Int,
)

data class MatchDetails(
    val match: Match,
    val venue: String?,
    val events: List<MatchEvent>,
    val stats: List<MatchStat>,
    val broadcasters: List<String>,
    val referee: String? = null,
    /** the two teams' rows in their league / group table */
    val standings: List<StandingRow> = emptyList(),
    val standingsTitle: String? = null,
)

/**
 * Today's football matches from ESPN's public scoreboard JSON
 * (site.api.espn.com/apis/site/v2/sports/soccer/{league}/scoreboard) — no key needed.
 */
object Matches {
    private const val BASE = "https://site.api.espn.com/apis/site/v2/sports/soccer"
    private var cache: Pair<Long, List<Match>>? = null
    private var cacheKey = ""

    /** Last source that answered, shown in the app ("Sofascore" or "ESPN"). */
    @Volatile var lastSource = ""

    /**
     * Sofascore first (Arabic names, Saudi TV channels, tennis); ESPN if Sofascore can't be reached.
     */
    suspend fun todayAll(leagues: Set<String>, tennis: Boolean, force: Boolean = false): List<Match> {
        val key = "sofa|" + leagues.sorted().joinToString(",") + "|$tennis"
        val c = cache
        if (!force && c != null && key == cacheKey && System.currentTimeMillis() - c.first < 60_000) return c.second
        val sofa = runCatching { Sofa.today(leagues, tennis) }.getOrNull()
        val list = if (sofa != null) { lastSource = "Sofascore"; sofa } else { lastSource = "ESPN"; today(leagues, force = true) }
        cache = System.currentTimeMillis() to list
        cacheKey = key
        return list
    }

    /** Match centre from the source the match came from. */
    suspend fun detailsAny(m: Match): MatchDetails = if (m.source == "sofa") Sofa.details(m) else details(m)

    suspend fun today(leagues: Collection<String>, force: Boolean = false): List<Match> = withContext(Dispatchers.IO) {
        val key = leagues.sorted().joinToString(",")
        val c = cache
        if (!force && c != null && key == cacheKey && System.currentTimeMillis() - c.first < 5 * 60_000) return@withContext c.second

        // "Today" in the TV's own time zone, which may span two UTC dates
        val local = SimpleDateFormat("yyyyMMdd", Locale.US)
        val utc = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val now = Date()
        val dayStart = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val dayEnd = dayStart + 24 * 3600_000L
        val dates = setOf(utc.format(Date(dayStart)), utc.format(Date(dayEnd - 1)), local.format(now))

        val list = coroutineScope {
            leagues.flatMap { lg -> dates.map { d -> async { runCatching { fetch(lg, d) }.getOrDefault(emptyList()) } } }
                .awaitAll().flatten()
        }
            .distinctBy { it.id }
            .filter { it.start in dayStart until dayEnd || it.isLive }
            .sortedWith(compareBy<Match>({ if (it.isLive) 0 else if (it.isFinished) 2 else 1 }, { it.start }))
        cache = System.currentTimeMillis() to list
        cacheKey = key
        list
    }

    private fun fetch(league: String, date: String): List<Match> {
        val json = JSONObject(Http.get("$BASE/$league/scoreboard?dates=$date&limit=100", timeoutMs = 12_000))
        val leagueName = json.optJSONArray("leagues")?.optJSONObject(0)?.let {
            it.optString("abbreviation").ifBlank { it.optString("name") }
        } ?: league
        val events = json.optJSONArray("events") ?: return emptyList()
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val iso2 = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val out = ArrayList<Match>()
        for (i in 0 until events.length()) {
            val e = events.getJSONObject(i)
            val comp = e.optJSONArray("competitions")?.optJSONObject(0) ?: continue
            val teams = comp.optJSONArray("competitors") ?: continue
            var home: Team? = null
            var away: Team? = null
            for (t in 0 until teams.length()) {
                val ct = teams.getJSONObject(t)
                val team = ct.optJSONObject("team") ?: continue
                val tm = Team(
                    name = team.optString("displayName"),
                    shortName = team.optString("shortDisplayName").ifBlank { team.optString("displayName") },
                    logo = team.optString("logo").ifBlank { null },
                    score = ct.optString("score").ifBlank { null },
                )
                if (ct.optString("homeAway") == "home") home = tm else away = tm
            }
            if (home == null || away == null) continue
            val dateStr = e.optString("date")
            val start = runCatching { iso.parse(dateStr)!!.time }.recoverCatching { iso2.parse(dateStr)!!.time }.getOrNull() ?: continue
            val status = e.optJSONObject("status")?.optJSONObject("type")
            val broadcasts = ArrayList<String>()
            comp.optJSONArray("broadcasts")?.let { b ->
                for (k in 0 until b.length()) {
                    val names = b.getJSONObject(k).optJSONArray("names") ?: continue
                    for (n in 0 until names.length()) broadcasts += names.getString(n)
                }
            }
            out += Match(
                id = e.optString("id"),
                league = leagueName,
                leagueCode = league,
                start = start,
                state = status?.optString("state") ?: "pre",
                statusText = status?.optString("shortDetail") ?: "",
                home = home, away = away,
                broadcasts = broadcasts,
            )
        }
        return out
    }

    /** Goals, cards, stats, venue and broadcasters (ESPN "summary" for one event). */
    suspend fun details(m: Match): MatchDetails = withContext(Dispatchers.IO) {
        val json = JSONObject(Http.get("$BASE/${m.leagueCode}/summary?event=${m.id}", timeoutMs = 12_000))

        // refreshed score / status
        var match = m
        var homeTeamId: String? = null
        json.optJSONObject("header")?.optJSONArray("competitions")?.optJSONObject(0)?.let { comp ->
            val status = comp.optJSONObject("status")?.optJSONObject("type")
            var hs = m.home.score; var aws = m.away.score
            var homeId: String? = null
            comp.optJSONArray("competitors")?.let { cs ->
                for (i in 0 until cs.length()) {
                    val c = cs.getJSONObject(i)
                    val score = c.optString("score").ifBlank { null }
                    if (c.optString("homeAway") == "home") { hs = score ?: hs; homeId = c.optString("id") } else aws = score ?: aws
                }
            }
            match = m.copy(
                state = status?.optString("state") ?: m.state,
                statusText = status?.optString("shortDetail") ?: m.statusText,
                home = m.home.copy(score = hs), away = m.away.copy(score = aws),
            )
            homeTeamId = homeId
        }
        val homeId = homeTeamId

        // goals and cards
        val events = ArrayList<MatchEvent>()
        json.optJSONArray("keyEvents")?.let { arr ->
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                val typeText = (e.optJSONObject("type")?.optString("text") ?: "").lowercase(Locale.ROOT)
                val typeId = (e.optJSONObject("type")?.optString("type") ?: "").lowercase(Locale.ROOT)
                val kind = when {
                    "own goal" in typeText -> "own_goal"
                    "penalty" in typeText && ("goal" in typeText || "scored" in typeText) -> "penalty"
                    e.optBoolean("scoringPlay") || "goal" in typeText || typeId.startsWith("goal") -> "goal"
                    "red" in typeText -> "red"
                    "yellow" in typeText -> "yellow"
                    else -> continue
                }
                if ("missed" in typeText || "saved" in typeText) continue
                val minute = e.optJSONObject("clock")?.optString("displayValue") ?: ""
                val player = e.optJSONArray("participants")?.optJSONObject(0)?.optJSONObject("athlete")
                    ?.optString("displayName")?.ifBlank { null } ?: e.optString("shortText")
                val teamId = e.optJSONObject("team")?.optString("id")
                events += MatchEvent(kind, minute, player, home = teamId == null || teamId == homeId)
            }
        }

        // a few key statistics
        val wanted = linkedMapOf(
            "possessionPct" to "الاستحواذ %", "totalShots" to "التسديدات", "shotsOnTarget" to "على المرمى",
            "wonCorners" to "الركنيات", "foulsCommitted" to "الأخطاء", "offsides" to "التسلل",
            "yellowCards" to "البطاقات الصفراء", "saves" to "التصديات",
        )
        val stats = ArrayList<MatchStat>()
        json.optJSONObject("boxscore")?.optJSONArray("teams")?.let { teams ->
            if (teams.length() == 2) {
                fun statsOf(i: Int): Map<String, String> {
                    val t = teams.getJSONObject(i)
                    val out = HashMap<String, String>()
                    val arr = t.optJSONArray("statistics") ?: return out
                    for (k in 0 until arr.length()) {
                        val s = arr.getJSONObject(k)
                        out[s.optString("name")] = s.optString("displayValue")
                    }
                    return out
                }
                val firstIsHome = teams.getJSONObject(0).optJSONObject("team")?.optString("id") == homeId
                val h = statsOf(if (firstIsHome) 0 else 1)
                val a = statsOf(if (firstIsHome) 1 else 0)
                wanted.forEach { (key, label) ->
                    val hv = h[key]; val av = a[key]
                    if (!hv.isNullOrBlank() && !av.isNullOrBlank()) stats += MatchStat(label, hv, av)
                }
            }
        }

        val venue = json.optJSONObject("gameInfo")?.optJSONObject("venue")?.let { v ->
            listOfNotNull(v.optString("fullName").ifBlank { null },
                v.optJSONObject("address")?.optString("city")?.ifBlank { null }).joinToString(" — ").ifBlank { null }
        }

        val broadcasters = LinkedHashSet<String>(m.broadcasts)
        json.optJSONArray("broadcasts")?.let { b ->
            for (i in 0 until b.length()) {
                val o = b.getJSONObject(i)
                (o.optJSONObject("media")?.optString("shortName") ?: o.optString("station")).takeIf { !it.isNullOrBlank() }?.let { broadcasters += it }
            }
        }
        MatchDetails(match, venue, events, stats, broadcasters.toList())
    }

    /**
     * Channels that may carry the match: channel names that mention a team or a broadcaster,
     * then the sports channels of the subscription. Linear in the number of channels.
     */
    /**
     * Subscription channels whose name is one of the official broadcasters
     * ("beIN SPORTS HD 1" ↔ "AR: beIN Sports 1 FHD"). Quality / country tags are ignored,
     * and "beIN 1" never matches "beIN 10".
     */
    fun broadcasterChannels(broadcasters: List<String>, channels: List<Channel>): List<Channel> {
        if (broadcasters.isEmpty()) return emptyList()
        val noise = Regex("\\b(hd|fhd|uhd|sd|4k|8k|hevc|h265|ar|ksa|sa|mena|me|arabic|tv|channel|live|backup|raw)\\b")
        fun norm(s: String) = s.lowercase(Locale.ROOT)
            .replace(Regex("^[a-z]{2,4}\\s*[:|\\-]\\s*"), "")     // "AR: ", "KSA | "
            .replace(noise, " ")
            .filter { it.isLetterOrDigit() }
        val wanted = broadcasters.map(::norm).filter { it.length >= 3 }.distinct()
        if (wanted.isEmpty()) return emptyList()
        return channels.filter { ch ->
            val n = norm(ch.name)
            wanted.any { w ->
                val i = n.indexOf(w)
                i >= 0 && (i + w.length >= n.length || !(w.last().isDigit() && n[i + w.length].isDigit()))
            }
        }.distinctBy { it.id }.take(30)
    }

    fun candidateChannels(m: Match, channels: List<Channel>, categories: List<Category>): List<Channel> {
        val clean = Regex("[^\\p{L}\\p{N}]")
        fun norm(s: String) = s.lowercase(Locale.ROOT).replace(clean, "")
        val words = (listOf(m.home.name, m.away.name, m.home.shortName, m.away.shortName) + m.broadcasts)
            .map(::norm).filter { it.length >= 4 }.distinct()
        val sportsCats = categories.filter {
            val n = it.name.lowercase(Locale.ROOT)
            listOf("sport", "رياض", "bein", "ssc", "football", "كرة", "thmanyah", "ثمانية", "shahid").any { k -> n.contains(k) }
        }.mapTo(HashSet()) { it.id }

        val direct = ArrayList<Channel>()
        val sports = ArrayList<Channel>()
        for (ch in channels) {
            val n = norm(ch.name)
            when {
                words.any { n.contains(it) } -> if (direct.size < 60) direct += ch
                ch.categoryId in sportsCats -> if (sports.size < 140) sports += ch
            }
            if (direct.size >= 60 && sports.size >= 140) break
        }
        return direct + sports
    }

    // ---------- filter & order ----------

    /** Higher = more important. */
    private val leagueWeight = mapOf(
        "uefa.champions" to 100, "fifa.world" to 100, "fifa.cwc" to 95, "ksa.1" to 90, "eng.1" to 88,
        "esp.1" to 87, "afc.champions" to 85, "ita.1" to 80, "ger.1" to 78, "fra.1" to 75, "uefa.europa" to 72,
        "ksa.kings.cup" to 70, "uefa.euro" to 95, "afc.asian.cup" to 90, "caf.nations" to 80, "egy.1" to 65,
        "esp.copa_del_rey" to 62, "eng.fa" to 62, "uefa.nations" to 60, "uefa.europa.conf" to 55,
    )
    private val bigTeams = listOf(
        "real madrid", "barcelona", "al hilal", "al-hilal", "al nassr", "al-nassr", "al ittihad", "al-ittihad",
        "al ahli", "al-ahli", "manchester city", "liverpool", "arsenal", "manchester united", "chelsea",
        "bayern", "paris saint-germain", "psg", "juventus", "inter", "ac milan", "atletico", "al ahly", "zamalek",
        "saudi arabia", "argentina", "brazil", "france", "spain", "england", "portugal", "germany", "morocco", "egypt",
        // Arabic names (Sofascore)
        "الهلال", "النصر", "الاتحاد", "الأهلي", "ريال مدريد", "برشلونة", "مانشستر سيتي", "ليفربول", "آرسنال",
        "مانشستر يونايتد", "تشيلسي", "بايرن", "باريس", "يوفنتوس", "إنتر", "ميلان", "أتلتيكو", "الزمالك",
        "السعودية", "الأرجنتين", "البرازيل", "فرنسا", "إسبانيا", "إنجلترا", "البرتغال", "ألمانيا", "المغرب", "مصر",
    )

    fun importance(m: Match): Int {
        var w = leagueWeight[m.leagueCode] ?: if (m.leagueCode.startsWith("fifa.worldq")) 58 else 40
        if (m.sport == "tennis") {
            val l = m.league.lowercase(Locale.ROOT)
            w = when {
                listOf("australian open", "roland garros", "french open", "wimbledon", "us open").any { it in l } -> 95
                "finals" in l -> 85
                "masters" in l || "1000" in l -> 75
                else -> 50
            }
        }
        val names = (m.home.name + " " + m.away.name).lowercase(Locale.ROOT)
        bigTeams.forEach { if (names.contains(it)) w += 15 }
        if (m.isLive) w += 50
        if (m.isFinished) w -= 60
        return w
    }

    /** mode: "all" | "live" | "upcoming";  sort: "time" | "importance" */
    fun arrange(list: List<Match>, mode: String, sort: String): List<Match> {
        val filtered = when (mode) {
            "live" -> list.filter { it.isLive }
            "upcoming" -> list.filter { !it.isFinished }
            else -> list
        }
        return if (sort == "importance") filtered.sortedWith(compareByDescending<Match> { importance(it) }.thenBy { it.start })
        else filtered
    }
}
