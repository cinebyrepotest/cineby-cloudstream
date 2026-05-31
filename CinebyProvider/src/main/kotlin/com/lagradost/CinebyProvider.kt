package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

data class CinebyResponse(val results: List<CinebyItem>?)
data class CinebyItem(val id: Int?, val title: String?, val name: String?, val posterPath: String?, val mediaType: String?)
data class CinebyDetail(val id: Int?, val title: String?, val name: String?, val overview: String?, val posterPath: String?, val releaseDate: String?, val firstAirDate: String?, val seasons: List<CinebySeason>?)
data class CinebySeason(val seasonNumber: Int?, val episodeCount: Int?)
data class CinebyStream(val sources: List<CinebySource>?)
data class CinebySource(val url: String?, val quality: String?)

class CinebyProvider : MainAPI() {
    override var mainUrl = "https://www.cineby.sc"
    override var name = "Cineby"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/api/content/trending?type=movie" to "Trending Movies",
        "$mainUrl/api/content/trending?type=tv" to "Trending TV Shows",
        "$mainUrl/api/content/popular?type=movie" to "Popular Movies",
        "$mainUrl/api/content/popular?type=tv" to "Popular TV Shows",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val response = app.get(request.data).parsedSafe<CinebyResponse>()
        val items = response?.results?.map { it.toSearchResult() } ?: emptyList()
        return newHomePageResponse(request.name, items)
    }

    private fun CinebyItem.toSearchResult(): SearchResponse {
        return if (mediaType == "movie") {
            newMovieSearchResponse(title ?: name ?: "", "$mainUrl/movie/$id", TvType.Movie) {
                this.posterUrl = getPoster(posterPath)
            }
        } else {
            newTvSeriesSearchResponse(title ?: name ?: "", "$mainUrl/tv/$id", TvType.TvSeries) {
                this.posterUrl = getPoster(posterPath)
            }
        }
    }

    private fun getPoster(path: String?) = path?.let { "https://image.tmdb.org/t/p/w500$it" }

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get("$mainUrl/api/search?query=$query").parsedSafe<CinebyResponse>()
        return response?.results?.map { it.toSearchResult() } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val isMovie = url.contains("/movie/")
        val id = url.substringAfterLast("/")
        val apiUrl = if (isMovie) "$mainUrl/api/content/movie/$id" else "$mainUrl/api/content/tv/$id"
        val detail = app.get(apiUrl).parsedSafe<CinebyDetail>()

        return if (isMovie) {
            newMovieLoadResponse(detail?.title ?: "", url, TvType.Movie, url) {
                this.posterUrl = getPoster(detail?.posterPath)
                this.plot = detail?.overview
                this.year = detail?.releaseDate?.take(4)?.toIntOrNull()
            }
        } else {
            val episodes = detail?.seasons?.flatMap { season ->
                (1..(season.episodeCount ?: 0)).map { ep ->
                    newEpisode("$url/season/${season.seasonNumber}/episode/$ep") {
                        this.season = season.seasonNumber
                        this.episode = ep
                    }
                }
            } ?: emptyList()
            newTvSeriesLoadResponse(detail?.name ?: "", url, TvType.TvSeries, episodes) {
                this.posterUrl = getPoster(detail?.posterPath)
                this.plot = detail?.overview
                this.year = detail?.firstAirDate?.take(4)?.toIntOrNull()
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val isEpisode = data.contains("/season/")
        val streamUrl = if (isEpisode) {
            val parts = data.split("/")
            val tvId = parts[parts.indexOf("tv") + 1]
            val season = parts[parts.indexOf("season") + 1]
            val episode = parts[parts.indexOf("episode") + 1]
            "$mainUrl/api/stream/tv/$tvId/$season/$episode"
        } else {
            "$mainUrl/api/stream/movie/${data.substringAfterLast("/")}"
        }

        val response = app.get(streamUrl).parsedSafe<CinebyStream>()
        response?.sources?.forEach { source ->
            val srcUrl = source.url ?: return@forEach
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = srcUrl,
                    type = if (srcUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                ) {
                    this.referer = mainUrl
                    this.quality = when (source.quality) {
                        "4k" -> Qualities.P2160.value
                        "1080p" -> Qualities.P1080.value
                        "720p" -> Qualities.P720.value
                        "480p" -> Qualities.P480.value
                        else -> Qualities.Unknown.value
                    }
                }
            )
        }
        return true
    }
}
