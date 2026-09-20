package com.megix

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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

    private val apiUrl = "https://blakiteapi.xyz/api/getAllAnime.php"
    private val playerBase = "https://blakiteapi.xyz/"

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
    // API
    // ---------------------------------------------------------

    private suspend fun fetchAll(): AnimeData {
        return try {
            val response = app.get(
                apiUrl,
                headers = mapOf(
                    "User-Agent" to ua,
                    "Accept" to "application/json, text/plain, */*",
                    "Referer" to playerBase
                ),
                allowRedirects = true
            )

            if (response.code !in 200..299) {
                Log.e("SubDub", "API HTTP ${response.code}")
                return AnimeData()
            }

            tryParseJson<ApiResponse>(response.text)?.data ?: AnimeData()

        } catch (e: Exception) {
            Log.e("SubDub", "fetchAll failed: ${e.message}")
            AnimeData()
        }
    }

    // ---------------------------------------------------------
    // SEARCH RESPONSE
    // ---------------------------------------------------------

    private fun AnimeItem.toSearchResponse(): SearchResponse? {
        val id = tmdbId ?: return null
        val title = title ?: return null

        return if (type?.equals("Series", true) == true) {
            newTvSeriesSearchResponse(title, id, TvType.TvSeries) {
                posterUrl = images?.poster
            }
        } else {
            newMovieSearchResponse(title, id, TvType.Movie) {
                posterUrl = images?.poster
            }
        }
    }

    // ---------------------------------------------------------
    // LOAD
    // ---------------------------------------------------------

    override suspend fun load(url: String): LoadResponse? {
        val data = fetchAll()

        val cleanUrl = url.substringBefore("?").trimEnd('/')
        val lookupId = cleanUrl.substringAfterLast('/')

        Log.d("SubDub", "load url=$url lookupId=$lookupId")

        val item =
            data.movies[lookupId]
                ?: data.series[lookupId]
                ?: data.movies[url]
                ?: data.series[url]
                ?: (data.movies.values + data.series.values)
                    .firstOrNull { it.tmdbId == lookupId || it.tmdbId == url }
                ?: run {
                    Log.e("SubDub", "No catalogue item found for $url")
                    return null
                }

        val title = item.title ?: "Unknown"
        val poster = item.images?.poster
        val backdrop = item.images?.backdrop
        val plot = item.tmdbData?.synopsis
        val year = item.tmdbData?.releaseDate?.substringBefore("-")?.toIntOrNull()
        val rating = item.tmdbData?.rating?.toDoubleOrNull()
        val genres = item.tmdbData?.genres ?: emptyList()
        val tmdbId = item.tmdbId ?: lookupId
        val isSeries = item.type?.equals("Series", true) == true

        if (isSeries) {
            val episodes = mutableListOf<Episode>()
            val seasons = item.seasons ?: emptyMap()

            seasons.forEach { (key, info) ->
                val seasonNumber = info.seasonNumber ?: key.toIntOrNull() ?: 1
                val totalEpisodes = info.totalEpisodes ?: 0

                for (episodeNumber in 1..totalEpisodes) {
                    episodes.add(
                        newEpisode("$tmdbId|$seasonNumber|$episodeNumber|series") {
                            name = "S$seasonNumber E$episodeNumber"
                            season = seasonNumber
                            episode = episodeNumber
                            posterUrl = poster
                        }
                    )
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                tags = genres
                score = Score.from10(rating)
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, "$tmdbId|1|1|movie") {
                posterUrl = poster
                backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                tags = genres
                score = Score.from10(rating)
            }
        }
    }

    // ---------------------------------------------------------
    // LINK LOADING  (Multi Quality + MP4 + M3U8)
    // ---------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val parts = data.split("|")
        val tmdbId = parts.getOrNull(0) ?: return false
        val season = parts.getOrNull(1)?.toIntOrNull() ?: 1
        val episode = parts.getOrNull(2)?.toIntOrNull() ?: 1
        val type = parts.getOrNull(3) ?: "movie"

        Log.d("SubDub", "loadLinks data=$data")

        val isMovie = type.equals("movie", true)
        val uniqueId = if (isMovie) null else "$season-$episode"

        val apiCallUrl = if (isMovie) {
            "https://blakiteapi.xyz/api/get.php?tmdbId=$tmdbId"
        } else {
            "https://blakiteapi.xyz/api/get.php?id=$uniqueId&tmdbId=$tmdbId"
        }

        val referer = if (isMovie) {
            "https://blakiteapi.xyz/embed/$tmdbId"
        } else {
            "https://blakiteapi.xyz/embed/$tmdbId/$uniqueId"
        }

        return try {
            val response = app.get(
                apiCallUrl,
                headers = mapOf(
                    "User-Agent" to ua,
                    "Referer" to referer,
                    "Origin" to "https://blakiteapi.xyz",
                    "Accept" to "application/json"
                )
            )

            if (response.code !in 200..299) {
                Log.e("SubDub", "HTTP ${response.code}")
                return false
            }

            val json = JSONObject(response.text)
            if (!json.optBoolean("success", false)) {
                Log.e("SubDub", "success=false")
                return false
            }

            val d = json.optJSONObject("data") ?: return false
            val dataId = d.optString("dataId")
            val format = d.optString("format").uppercase()
            val ranges = d.optString("ranges")
            val qualityStr = d.optString("quality").ifBlank { "480p" }

            if (dataId.isBlank()) {
                Log.e("SubDub", "empty dataId")
                return false
            }

            var found = false

            // Case 1: MP4 (mostly movies, sometimes series)
            if (format == "MP4" || ranges.isBlank()) {
                val mp4 = "https://hugh.cdn.rumble.cloud/video/$dataId.caa.mp4"

                val quality = when {
                    qualityStr.contains("1080") -> Qualities.P1080.value
                    qualityStr.contains("720")  -> Qualities.P720.value
                    qualityStr.contains("480")  -> Qualities.P480.value
                    qualityStr.contains("360")  -> Qualities.P360.value
                    else -> Qualities.Unknown.value
                }

                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = "$name $qualityStr",
                        url = mp4,
                        referer = mainUrl,
                        quality = quality,
                        type = ExtractorLinkType.VIDEO
                    )
                )
                found = true
            }

            // Case 2: M3U8 multi quality (ranges present)
            if (ranges.isNotBlank()) {
                ranges.lines().forEach { line ->
                    val match = Regex("""(\d+-\d+)\s*\((\d+p)\)""").find(line.trim()) ?: return@forEach
                    val range = match.groupValues[1]
                    val q = match.groupValues[2]

                    val m3u8 =
                        "https://hugh.cdn.rumble.cloud/video/$dataId.caa.tar" +
                        "?r_file=chunklist.m3u8&r_type=application%2Fvnd.apple.mpegurl&r_range=$range"

                    val quality = when {
                        q.contains("1080") -> Qualities.P1080.value
                        q.contains("720")  -> Qualities.P720.value
                        q.contains("480")  -> Qualities.P480.value
                        q.contains("360")  -> Qualities.P360.value
                        q.contains("240")  -> Qualities.P240.value
                        else -> Qualities.Unknown.value
                    }

                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = "$name $q",
                            url = m3u8,
                            referer = mainUrl,
                            quality = quality,
                            type = ExtractorLinkType.M3U8
                        )
                    )
                    found = true
                }
            }

            // Fallback: try plain m3u8 without range if nothing found
            if (!found) {
                val fallback = "https://hugh.cdn.rumble.cloud/video/$dataId.caa.tar?r_file=chunklist.m3u8&r_type=application%2Fvnd.apple.mpegurl"
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = "$name Auto",
                        url = fallback,
                        referer = mainUrl,
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.M3U8
                    )
                )
                found = true
            }

            Log.d("SubDub", "found=$found format=$format")
            found

        } catch (e: Exception) {
            Log.e("SubDub", "Exception: ${e.message}")
            false
        }
    }

    // ---------------------------------------------------------
    // DATA MODELS
    // ---------------------------------------------------------

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
