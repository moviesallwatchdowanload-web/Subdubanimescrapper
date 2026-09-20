package com.megix

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.json.JSONObject

class SubDubAnimeProvider : MainAPI() {

    override var mainUrl = "https://www.subdubanime.site"
    override var name = "SubDub Anime"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Movie,
        TvType.TvSeries
    )

    private val listApi = "https://blakiteapi.xyz/api/getAllAnime.php"
    private val getApi = "https://blakiteapi.xyz/api/get.php"
    private val playerBase = "https://blakiteapi.xyz"

    private val ua =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"

    override val mainPage = mainPageOf(
        "all" to "All Anime",
        "movies" to "Movies",
        "series" to "Series",
        "hindi" to "Hindi Dubbed",
        "fandub" to "Hindi Fan Dub",
        "engsub" to "English Subbed"
    )

    // ---------------------------------------------------------
    // MAIN PAGE
    // ---------------------------------------------------------

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val data = fetchAll()
        val allItems = (data.movies.values + data.series.values).toList()

        val filtered = when (request.data) {
            "movies" -> data.movies.values.toList()
            "series" -> data.series.values.toList()
            "hindi" -> allItems.filter {
                it.language?.contains("Hindi", true) == true
            }
            "fandub" -> allItems.filter {
                it.language?.contains("Fan", true) == true ||
                it.language?.contains("Fandub", true) == true
            }
            "engsub" -> allItems.filter {
                it.language?.contains("English Subbed", true) == true
            }
            else -> allItems
        }

        return newHomePageResponse(
            request.name,
            filtered.mapNotNull { it.toSearchResponse() }
        )
    }

    // ---------------------------------------------------------
    // SEARCH
    // ---------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        val data = fetchAll()
        val all = (data.movies.values + data.series.values).toList()

        return all
            .filter { it.title?.contains(query, true) == true }
            .mapNotNull { it.toSearchResponse() }
    }

    // ---------------------------------------------------------
    // FETCH CATALOGUE
    // ---------------------------------------------------------

    private suspend fun fetchAll(): AnimeData {
        return try {
            val response = app.get(
                listApi,
                headers = mapOf(
                    "User-Agent" to ua,
                    "Accept" to "application/json",
                    "Referer" to "$playerBase/"
                )
            )
            if (response.code !in 200..299) return AnimeData()
            tryParseJson<ApiResponse>(response.text)?.data ?: AnimeData()
        } catch (e: Exception) {
            Log.e("SubDub", "fetchAll: ${e.message}")
            AnimeData()
        }
    }

    private fun AnimeItem.toSearchResponse(): SearchResponse? {
        val id = tmdbId ?: return null
        val title = title ?: return null

        return if (type?.equals("Series", true) == true) {
            newTvSeriesSearchResponse(title, id, TvType.TvSeries) {
                this.posterUrl = images?.poster
            }
        } else {
            newMovieSearchResponse(title, id, TvType.Movie) {
                this.posterUrl = images?.poster
            }
        }
    }

    // ---------------------------------------------------------
    // LOAD
    // ---------------------------------------------------------

    override suspend fun load(url: String): LoadResponse? {
        val data = fetchAll()

        val lookupId = url.substringBefore("?").trimEnd('/').substringAfterLast('/')

        val item =
            data.movies[lookupId]
                ?: data.series[lookupId]
                ?: (data.movies.values + data.series.values)
                    .firstOrNull { it.tmdbId == lookupId || it.tmdbId == url }
                ?: return null

        val title = item.title ?: "Unknown"
        val poster = item.images?.poster
        val backdrop = item.images?.backdrop
        val plot = item.tmdbData?.synopsis
        val year = item.tmdbData?.releaseDate?.substringBefore("-")?.toIntOrNull()
        val rating = item.tmdbData?.rating
        val genres = item.tmdbData?.genres ?: emptyList()
        val tmdbId = item.tmdbId ?: lookupId
        val isSeries = item.type?.equals("Series", true) == true

        if (isSeries) {
            val episodes = mutableListOf<Episode>()
            val seasons = item.seasons ?: emptyMap()

            seasons.forEach { (key, info) ->
                val seasonNumber = info.seasonNumber ?: key.toIntOrNull() ?: 1
                val total = info.totalEpisodes ?: 0

                for (ep in 1..total) {
                    // Same style as Vega: pass list of EpisodeLink as data
                    val links = listOf(
                        EpisodeLink(
                            source = "$tmdbId|$seasonNumber|$ep|series"
                        )
                    )
                    episodes.add(
                        newEpisode(links) {
                            this.name = "S$seasonNumber E$ep"
                            this.season = seasonNumber
                            this.episode = ep
                            this.posterUrl = poster
                        }
                    )
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.tags = genres
                this.score = Score.from10(rating)
            }
        } else {
            val links = listOf(
                EpisodeLink(source = "$tmdbId|1|1|movie")
            )
            return newMovieLoadResponse(title, url, TvType.Movie, links) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.tags = genres
                this.score = Score.from10(rating)
            }
        }
    }

    // ---------------------------------------------------------
    // LOAD LINKS  (Vega style + blakite API)
    // ---------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val sources = try {
            parseJson<ArrayList<EpisodeLink>>(data)
        } catch (e: Exception) {
            // fallback if data is plain string
            listOf(EpisodeLink(data))
        }

        var found = false

        sources.forEach { link ->
            val parts = link.source.split("|")
            val tmdbId = parts.getOrNull(0) ?: return@forEach
            val season = parts.getOrNull(1)?.toIntOrNull() ?: 1
            val episode = parts.getOrNull(2)?.toIntOrNull() ?: 1
            val type = parts.getOrNull(3) ?: "movie"

            val isMovie = type.equals("movie", true)
            val uniqueId = if (isMovie) null else "$season-$episode"

            val apiUrl = if (isMovie) {
                "$getApi?tmdbId=$tmdbId"
            } else {
                "$getApi?id=$uniqueId&tmdbId=$tmdbId"
            }

            val referer = if (isMovie) {
                "$playerBase/embed/$tmdbId"
            } else {
                "$playerBase/embed/$tmdbId/$uniqueId"
            }

            try {
                val response = app.get(
                    apiUrl,
                    headers = mapOf(
                        "User-Agent" to ua,
                        "Referer" to referer,
                        "Origin" to playerBase,
                        "Accept" to "application/json"
                    )
                )

                if (response.code !in 200..299) return@forEach

                val json = JSONObject(response.text)
                if (!json.optBoolean("success", false)) return@forEach

                val d = json.optJSONObject("data") ?: return@forEach
                val dataId = d.optString("dataId")
                val format = d.optString("format").uppercase()
                val ranges = d.optString("ranges")
                val qualityStr = d.optString("quality").ifBlank { "480p" }

                if (dataId.isBlank()) return@forEach

                fun q(v: String): Int = when {
                    v.contains("1080") -> Qualities.P1080.value
                    v.contains("720")  -> Qualities.P720.value
                    v.contains("480")  -> Qualities.P480.value
                    v.contains("360")  -> Qualities.P360.value
                    v.contains("240")  -> Qualities.P240.value
                    else -> Qualities.Unknown.value
                }

                // MP4 (movies + many series)
                if (format == "MP4" || ranges.isBlank()) {
                    val mp4 = "https://hugh.cdn.rumble.cloud/video/$dataId.caa.mp4"
                  callback.invoke(
    newExtractorLink(
        name,
        "$name $qualityStr",
        mp4,
        type = ExtractorLinkType.VIDEO
    ) {
        this.referer = mainUrl
        this.quality = q(qualityStr)
    }
)
                    found = true
                }

                // Multi quality M3U8
                if (ranges.isNotBlank()) {
                    ranges.lines().forEach { line ->
                        val match = Regex("""(\d+-\d+)\s*\((\d+p)\)""").find(line.trim()) ?: return@forEach
                        val range = match.groupValues[1]
                        val qualityLabel = match.groupValues[2]

                        val m3u8 =
                            "https://hugh.cdn.rumble.cloud/video/$dataId.caa.tar" +
                            "?r_file=chunklist.m3u8&r_type=application%2Fvnd.apple.mpegurl&r_range=$range"

                   callback.invoke(
    newExtractorLink(
        name,
        "$name $qualityLabel",
        m3u8,
        type = ExtractorLinkType.M3U8
    ) {
        this.referer = mainUrl
        this.quality = q(qualityLabel)
    }
) 
                        found = true
                    }
                }

            } catch (e: Exception) {
                Log.e("SubDub", "loadLinks error: ${e.message}")
            }
        }

        return found
    }

    // ---------------------------------------------------------
    // DATA MODELS
    // ---------------------------------------------------------

    data class EpisodeLink(
        val source: String
    )

    data class ApiResponse(
        val success: Boolean? = null,
        val data: AnimeData = AnimeData()
    )

    data class AnimeData(
        val movies: Map<String, AnimeItem> = emptyMap(),
        val series: Map<String, AnimeItem> = emptyMap()
    )

    data class AnimeItem(
        val tmdbId: String? = null,
        @JsonProperty("originalTmdbId")
        val originalTmdbId: String? = null,
        val title: String? = null,
        val language: String? = null,
        val type: String? = null,
        val status: String? = null,
        @JsonProperty("TMDB_DATA")
        val tmdbData: TmdbData? = null,
        @JsonProperty("IMAGES")
        val images: Images? = null,
        val seasons: Map<String, SeasonInfo>? = null
    )

    data class TmdbData(
        val genres: List<String>? = null,
        val synopsis: String? = null,
        val rating: String? = null,
        val releaseDate: String? = null,
        val keywords: List<String>? = null,
        val trailer: String? = null
    )

    data class Images(
        val poster: String? = null,
        val backdrop: String? = null
    )

    data class SeasonInfo(
        val seasonNumber: Int? = null,
        val status: String? = null,
        val totalEpisodes: Int? = null
    )
}

