package com.multiview.tv.data

/** Parses #EXTM3U playlists (tvg-id, tvg-logo, group-title). */
object M3uParser {
    private val attr = Regex("""([\w-]+)="([^"]*)"""")

    data class Result(val categories: List<Category>, val channels: List<Channel>)

    fun parse(text: String): Result {
        val channels = ArrayList<Channel>()
        val groups = LinkedHashMap<String, Category>()
        var pendingInfo: String? = null
        var n = 0

        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> pendingInfo = line
                line.isEmpty() || line.startsWith("#") -> Unit
                pendingInfo != null -> {
                    val info = pendingInfo!!
                    val attrs = attr.findAll(info).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    val name = info.substringAfterLast(',').trim().ifBlank { "قناة ${n + 1}" }
                    val groupName = attrs["group-title"]?.ifBlank { null } ?: "بدون مجموعة"
                    val cat = groups.getOrPut(groupName) { Category(id = "g${groups.size}", name = groupName) }
                    n++
                    channels += Channel(
                        id = "m$n",
                        number = attrs["tvg-chno"]?.toIntOrNull() ?: n,
                        name = name,
                        logo = attrs["tvg-logo"]?.ifBlank { null },
                        categoryId = cat.id,
                        url = line,
                    )
                    pendingInfo = null
                }
            }
        }
        return Result(groups.values.toList(), channels)
    }
}
