package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mvvm.logError
import com.fasterxml.jackson.annotation.JsonProperty

class CinebyProvider : MainAPI() {

    override var name          = "Cineby"
    override var mainUrl       = "https://www.cineby.sc"
    override var lang          = "en"
    override val hasMainPage   = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val tmdbImageBase = "https://image.tmdb.org/t/p/w500"
    private val tmdbApiKey    = "8265bd1679663a7ea12ac168da84d2e8"
    private val tmdbBase      = "https://api.themoviedb.org/3"

    // ── Embed sources ────────────────────────────────────────────────────────

    private fun movieEmbeds(id: Int) = listOf(
        "https://embed.su/embed/movie/$id",
        "https://vidsrc.me/embed/movie?tmdb=$id",
        "https://vidlink.pro/movie/$id",
        "https://2embed.cc/embed/$id",
        "https://multiembed.mov/?video_id=$id&tmdb=1",
    )

    private fun tvEmbeds(id: Int, s: Int, e: Int) = listOf(
        "https://embed.su/embed/tv/$id/$s/$e",
        "https://vidsrc.me/embed/tv?tmdb=$id&season=$s&episode=$e",
        "https://vidlink.pro/tv/$id/$s/$e",
        "https://2embed.cc/embedtv/$id&s=$s&e=$e",
        "https://multiembed.mov/?video_id=$id&tmdb=1&s=$s&e=$e",
    )

    // ── Home page ─────────────────────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "$tmdbBase/trending/all/week?api_key=$tmdbApiKey"  to "Trending This Week",
        "$tmdbBase/movie/popular?api_key=$tmdbApiKey"      to "Popular Movies",
        "$tmdbBase/tv/popular?api_key=$tmdbApiKey"         to "Popular TV Shows",
        "$tmdbBase/movie/top_rated?api_key=$tmdbApiKey"    to "Top Rated Movies",
        "$tmdbBase/tv/top_rated?api_key=$tmdbApiKey"       to "Top Rated TV Shows",
        "$tmdbBase/movie/now_playing?api_key=$tmdbApiKey"  to "Now Playing",
        "$tmdbBase/movie/upcoming?api_key=$tmdbApiKey"     to "Upcoming Movies",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val resp  = app.get("${request.data}&page=$page").parsedSafe<TmdbPagedResult>()
                    ?: return newHomePageResponse(request.name, emptyList())
        val items = resp.results.mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items, hasNext = page < (resp.totalPages ?: 1))
    }

    // ── Search ────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val resp = app.get(
            "$tmdbBase/search/multi?api_key=$tmdbApiKey&query=${query.encodeUrlFragment()}&include_adult=false"
        ).parsedSafe<TmdbPagedResult>() ?: return emptyList()
        return resp.results.mapNotNull { it.toSearchResponse() }
    }

    // ── Load ──────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val parts  = url.split("|")
        val type   = parts.getOrNull(0) ?: return null
        val tmdbId = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return if (type == "movie") loadMovie(tmdbId) else loadTv(tmdbId)
    }

    private suspend fun loadMovie(id: Int): MovieLoadResponse? {
        val d = app.get("$tmdbBase/movie/$id?api_key=$tmdbApiKey&append_to_response=videos")
                    .parsedSafe<TmdbMovieDetail>() ?: return null
        val trailer = d.videos?.results
            ?.firstOrNull { it.site == "YouTube" && it.type == "Trailer" }
            ?.let { "https://www.youtube.com/watch?v=${it.key}" }

        return newMovieLoadResponse(
            name    = d.title ?: d.originalTitle ?: return null,
            url     = "movie|$id",
            type    = TvType.Movie,
            dataUrl = "movie|$id",
        ) {
            posterUrl           = d.posterPath?.let   { "$tmdbImageBase$it" }
            backgroundPosterUrl = d.backdropPath?.let { "$tmdbImageBase$it" }
            year                = d.releaseDate?.take(4)?.toIntOrNull()
            plot                = d.overview
            rating              = d.voteAverage?.times(1000)?.toInt()
            tags                = d.genres?.map { it.name }
            duration            = d.runtime
            addTrailer(trailer)
        }
    }

    private suspend fun loadTv(id: Int): TvSeriesLoadResponse? {
        val d = app.get("$tmdbBase/tv/$id?api_key=$tmdbApiKey&append_to_response=videos")
                    .parsedSafe<TmdbTvDetail>() ?: return null
        val trailer = d.videos?.results
            ?.firstOrNull { it.site == "YouTube" && it.type == "Trailer" }
            ?.let { "https://www.youtube.com/watch?v=${it.key}" }

        val episodes = mutableListOf<Episode>()
        for (s in 1..(d.numberOfSeasons ?: 1)) {
            val season = app.get("$tmdbBase/tv/$id/season/$s?api_key=$tmdbApiKey")
                             .parsedSafe<TmdbSeason>() ?: continue
            season.episodes?.forEach { ep ->
                episodes += newEpisode("tv|$id|$s|${ep.episodeNumber}") {
                    name        = ep.name
                    season      = s
                    episode     = ep.episodeNumber
                    posterUrl   = ep.stillPath?.let { "$tmdbImageBase$it" }
                    description = ep.overview
                    rating      = ep.voteAverage?.times(1000)?.toInt()
                }
            }
        }

        return newTvSeriesLoadResponse(
            name     = d.name ?: d.originalName ?: return null,
            url      = "tv|$id",
            type     = TvType.TvSeries,
            episodes = episodes,
        ) {
            posterUrl           = d.posterPath?.let   { "$tmdbImageBase$it" }
            backgroundPosterUrl = d.backdropPath?.let { "$tmdbImageBase$it" }
            year                = d.firstAirDate?.take(4)?.toIntOrNull()
            plot                = d.overview
            rating              = d.voteAverage?.times(1000)?.toInt()
            tags                = d.genres?.map { it.name }
            addTrailer(trailer)
        }
    }

    // ── Load links ────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val parts = data.split("|")
        val embeds = when (parts[0]) {
            "movie" -> movieEmbeds(parts.getOrNull(1)?.toIntOrNull() ?: return false)
            "tv"    -> tvEmbeds(
                parts.getOrNull(1)?.toIntOrNull() ?: return false,
                parts.getOrNull(2)?.toIntOrNull() ?: return false,
                parts.getOrNull(3)?.toIntOrNull() ?: return false,
            )
            else -> return false
        }
        var anyLoaded = false
        embeds.forEach { url ->
            try {
                if (loadExtractor(url, mainUrl, subtitleCallback, callback)) anyLoaded = true
            } catch (e: Exception) { logError(e) }
        }
        return anyLoaded
    }

    // ── Data classes ──────────────────────────────────────────────────────────

    data class TmdbPagedResult(
        @JsonProperty("results")     val results:    List<TmdbItem> = emptyList(),
        @JsonProperty("total_pages") val totalPages: Int?           = null,
    )

    data class TmdbItem(
        @JsonProperty("id")             val id:            Int?    = null,
        @JsonProperty("media_type")     val mediaType:     String? = null,
        @JsonProperty("title")          val title:         String? = null,
        @JsonProperty("name")           val name:          String? = null,
        @JsonProperty("original_title") val originalTitle: String? = null,
        @JsonProperty("original_name")  val originalName:  String? = null,
        @JsonProperty("poster_path")    val posterPath:    String? = null,
        @JsonProperty("release_date")   val releaseDate:   String? = null,
        @JsonProperty("first_air_date") val firstAirDate:  String? = null,
    ) {
        fun resolvedType() = when {
            mediaType != null -> mediaType
            title != null     -> "movie"
            name  != null     -> "tv"
            else              -> "unknown"
        }
        fun toSearchResponse(): SearchResponse? {
            val tmdbId    = id ?: return null
            val type      = resolvedType()
            val showTitle = title ?: name ?: originalTitle ?: originalName ?: return null
            val poster    = posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
            val year      = (releaseDate ?: firstAirDate)?.take(4)?.toIntOrNull()
            val encoded   = "$type|$tmdbId"
            return when (type) {
                "movie" -> newMovieSearchResponse(showTitle, encoded, TvType.Movie) {
                    posterUrl = poster; this.year = year
                }
                "tv"    -> newAnimeSearchResponse(showTitle, encoded, TvType.TvSeries) {
                    posterUrl = poster; this.year = year
                }
                else -> null
            }
        }
    }

    data class TmdbGenre(@JsonProperty("name") val name: String)

    data class TmdbVideo(
        @JsonProperty("key")  val key:  String,
        @JsonProperty("site") val site: String,
        @JsonProperty("type") val type: String,
    )

    data class TmdbVideos(@JsonProperty("results") val results: List<TmdbVideo> = emptyList())

    data class TmdbMovieDetail(
        @JsonProperty("title")          val title:         String?          = null,
        @JsonProperty("original_title") val originalTitle: String?          = null,
        @JsonProperty("poster_path")    val posterPath:    String?          = null,
        @JsonProperty("backdrop_path")  val backdropPath:  String?          = null,
        @JsonProperty("overview")       val overview:      String?          = null,
        @JsonProperty("release_date")   val releaseDate:   String?          = null,
        @JsonProperty("vote_average")   val voteAverage:   Double?          = null,
        @JsonProperty("runtime")        val runtime:       Int?             = null,
        @JsonProperty("genres")         val genres:        List<TmdbGenre>? = null,
        @JsonProperty("videos")         val videos:        TmdbVideos?      = null,
    )

    data class TmdbTvDetail(
        @JsonProperty("name")              val name:            String?          = null,
        @JsonProperty("original_name")     val originalName:    String?          = null,
        @JsonProperty("poster_path")       val posterPath:      String?          = null,
        @JsonProperty("backdrop_path")     val backdropPath:    String?          = null,
        @JsonProperty("overview")          val overview:        String?          = null,
        @JsonProperty("first_air_date")    val firstAirDate:    String?          = null,
        @JsonProperty("vote_average")      val voteAverage:     Double?          = null,
        @JsonProperty("number_of_seasons") val numberOfSeasons: Int?             = null,
        @JsonProperty("genres")            val genres:          List<TmdbGenre>? = null,
        @JsonProperty("videos")            val videos:          TmdbVideos?      = null,
    )

    data class TmdbSeason(
        @JsonProperty("episodes") val episodes: List<TmdbEpisode>? = null,
    )

    data class TmdbEpisode(
        @JsonProperty("episode_number") val episodeNumber: Int,
        @JsonProperty("name")           val name:          String? = null,
        @JsonProperty("overview")       val overview:      String? = null,
        @JsonProperty("still_path")     val stillPath:     String? = null,
        @JsonProperty("vote_average")   val voteAverage:   Double? = null,
    )
}
