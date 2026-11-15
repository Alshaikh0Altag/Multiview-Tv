package com.multiview.tv.data

data class Category(
    val id: String,
    val name: String,
)

data class Channel(
    val id: String,
    val number: Int,
    val name: String,
    val logo: String?,
    val categoryId: String,
    val url: String,
)

data class Movie(
    val id: String,
    val name: String,
    val poster: String?,
    val categoryId: String,
    val url: String,
    val rating: String?,
    /** unix seconds the provider added it (for "recently added") */
    val added: Long = 0,
)

/** Extra details from get_vod_info, shown on the movie info screen. */
data class MovieDetails(
    val plot: String?,
    val genre: String?,
    val director: String?,
    val cast: String?,
    val releaseDate: String?,
    val duration: String?,
    val rating: String?,
    val backdrop: String?,
    val poster: String?,
)

data class Series(
    val id: String,
    val name: String,
    val cover: String?,
    val categoryId: String,
    val plot: String?,
    val rating: String?,
    val added: Long = 0,
    val genre: String? = null,
    val releaseDate: String? = null,
)

data class Episode(
    val id: String,
    val season: Int,
    val number: Int,
    val title: String,
    val url: String,
    val plot: String?,
    val duration: String?,
)

data class SeriesDetails(
    val series: Series,
    val plot: String?,
    val cover: String?,
    /** season number → episodes, sorted */
    val seasons: Map<Int, List<Episode>>,
)

data class AccountInfo(
    val active: Boolean,
    val maxConnections: Int,
    val expiresAt: Long?,
)

/** Saved login. type = "xtream" or "m3u". */
data class Playlist(
    val type: String,
    val name: String,
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val m3uUrl: String = "",
)
