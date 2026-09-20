package com.megix

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.net.URI
import java.net.URLDecoder

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

        val allItems =
            (data.movies.values + data.series.values).toList()

        val filtered = when (request.data) {

            "movies" ->
                data.movies.values.toList()

            "series" ->
                data.series.values.toList()

            "hindi" ->
                allItems.filter {
                    it.language?.contains("Hindi", true) == true
                }

            "fandub" ->
                allItems.filter {
                    it.language?.contains("Fan", true) == true ||
                    it.language?.contains("Fandub", true) == true
                }

            "engsub" ->
                allItems.filter {
                    it.language?.contains("English Subbed", true) == true
                }

            else ->
                allItems
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

        val all =
            (data.movies.values + data.series.values).toList()

        return all
            .filter {
                it.title?.contains(query, true) == true
            }
            .mapNotNull {
                it.toSearchResponse()
            }
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
                Log.e(
                    "SubDub",
                    "API HTTP ${response.code}"
                )

                return AnimeData()
            }

            val json = response.text

            tryParseJson<ApiResponse>(json)?.data
                ?: AnimeData()

        } catch (e: Exception) {

            Log.e(
                "SubDub",
                "fetchAll failed: ${e.message}"
            )

            AnimeData()
        }
    }

    // ---------------------------------------------------------
    // SEARCH RESPONSE
    // ---------------------------------------------------------

    private fun AnimeItem.toSearchResponse(): SearchResponse? {

        val id = tmdbId ?: return null
        val title = title ?: return null

        return if (
            type?.equals("Series", true) == true
        ) {

            newTvSeriesSearchResponse(
                title,
                id,
                TvType.TvSeries
            ) {
                posterUrl = images?.poster
            }

        } else {

            newMovieSearchResponse(
                title,
                id,
                TvType.Movie
            ) {
                posterUrl = images?.poster
            }
        }
    }

    // ---------------------------------------------------------
    // LOAD
    // ---------------------------------------------------------

    override suspend fun load(url: String): LoadResponse? {

        val data = fetchAll()

        /*
         * CloudStream may pass:
         *
         * 0372058
         *
         * or:
         *
         * https://www.subdubanime.site/0372058
         *
         * or another provider URL containing the ID.
         */

        val cleanUrl = url
            .substringBefore("?")
            .trimEnd('/')

        val lookupId =
            cleanUrl.substringAfterLast('/')

        Log.d(
            "SubDub",
            "load url=$url lookupId=$lookupId"
        )

        val item =
            data.movies[lookupId]
                ?: data.series[lookupId]
                ?: data.movies[url]
                ?: data.series[url]
                ?: (data.movies.values + data.series.values)
                    .firstOrNull {
                        it.tmdbId == lookupId ||
                        it.tmdbId == url
                    }
                ?: run {

                    Log.e(
                        "SubDub",
                        "No catalogue item found for $url"
                    )

                    return null
                }

        val title =
            item.title ?: "Unknown"

        val poster =
            item.images?.poster

        val backdrop =
            item.images?.backdrop

        val plot =
            item.tmdbData?.synopsis

        val year =
            item.tmdbData
                ?.releaseDate
                ?.substringBefore("-")
                ?.toIntOrNull()

        val rating =
            item.tmdbData
                ?.rating
                ?.toDoubleOrNull()

        val genres =
            item.tmdbData?.genres ?: emptyList()

        val tmdbId =
            item.tmdbId ?: lookupId

        val isSeries =
            item.type?.equals("Series", true) == true

        if (isSeries) {

            val episodes =
                mutableListOf<Episode>()

            val seasons =
                item.seasons ?: emptyMap()

            seasons.forEach { (key, info) ->

                val seasonNumber =
                    info.seasonNumber
                        ?: key.toIntOrNull()
                        ?: 1

                val totalEpisodes =
                    info.totalEpisodes ?: 0

                for (episodeNumber in 1..totalEpisodes) {

                    episodes.add(
                        newEpisode(
                            "$tmdbId|$seasonNumber|$episodeNumber|series"
                        ) {

                            name =
                                "S$seasonNumber E$episodeNumber"

                            season =
                                seasonNumber

                            episode =
                                episodeNumber

                            posterUrl =
                                poster
                        }
                    )
                }
            }

            return newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes
            ) {

                posterUrl =
                    poster

                backgroundPosterUrl =
                    backdrop

                this.plot =
                    plot

                this.year =
                    year

                tags =
                    genres

                score =
                    Score.from10(rating)
            }

        } else {

            return newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                "$tmdbId|1|1|movie"
            ) {

                posterUrl =
                    poster

                backgroundPosterUrl =
                    backdrop

                this.plot =
                    plot

                this.year =
                    year

                tags =
                    genres

                score =
                    Score.from10(rating)
            }
        }
    }

    // ---------------------------------------------------------
    // LINK LOADING
    // ---------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val parts =
            data.split("|")

        val tmdbId =
            parts.getOrNull(0)
                ?: return false

        val season =
            parts.getOrNull(1)
                ?.toIntOrNull()
                ?: 1

        val episode =
            parts.getOrNull(2)
                ?.toIntOrNull()
                ?: 1

        val type =
            parts.getOrNull(3)
                ?: "movie"

        Log.d(
            "SubDub",
            "loadLinks tmdb=$tmdbId season=$season episode=$episode type=$type"
        )

        /*
         * Browser/player headers.
         *
         * These are ordinary HTTP headers only.
         * No DRM/authentication/anti-bot bypass is attempted.
         */

        val headers = mapOf(
            "User-Agent" to ua,
            "Referer" to "$playerBase/",
            "Origin" to playerBase.trimEnd('/'),
            "Accept" to "*/*"
        )

        /*
         * First try possible public player pages.
         *
         * The exact stream URL is NOT hard-coded.
         */

        val candidates =
            linkedSetOf<String>()

        candidates +=
            "$playerBase/watch/$tmdbId/$season/$episode"

        candidates +=
            "$playerBase/player/$tmdbId/$season/$episode"

        candidates +=
            "$playerBase/embed/$tmdbId/$season/$episode"

        candidates +=
            "$playerBase/?id=$tmdbId&s=$season&e=$episode"

        candidates +=
            "$playerBase/play/$tmdbId/$season/$episode"

        if (type.equals("series", true)) {

            candidates +=
                "$playerBase/watch/$tmdbId?s=$season&e=$episode"

            candidates +=
                "$playerBase/watch/$tmdbId-$season-$episode"

        } else {

            candidates +=
                "$playerBase/watch/$tmdbId"

            candidates +=
                "$playerBase/movie/$tmdbId"
        }

        candidates +=
            "$mainUrl/$tmdbId"

        candidates +=
            "$mainUrl/watch/$tmdbId/$season/$episode"

        /*
         * Regexes:
         *
         * 1. Standard .m3u8
         * 2. Rumble-style .tar? r_file=chunklist.m3u8
         * 3. Escaped JavaScript URLs
         */

        val normalM3u8Regex =
            Regex(
                """https?://[^"'<>\s\\]+\.m3u8(?:\?[^"'<>\s\\]*)?""",
                RegexOption.IGNORE_CASE
            )

        val rumbleRegex =
            Regex(
                """https?://[^"'<>\s\\]+\.tar\?[^"'<>\s\\]*r_file=chunklist\.m3u8[^"'<>\s\\]*""",
                RegexOption.IGNORE_CASE
            )

        val escapedRumbleRegex =
            Regex(
                """https?:\\/\\/[^"'<> ]+?\.tar\?[^"']*r_file=chunklist\.m3u8[^"']*""",
                RegexOption.IGNORE_CASE
            )

        val quotedM3u8Regex =
            Regex(
                """["'](https?://[^"']+\.m3u8[^"']*)["']""",
                RegexOption.IGNORE_CASE
            )

        for (pageUrl in candidates) {

            try {

                Log.d(
                    "SubDub",
                    "Trying player page: $pageUrl"
                )

                val response =
                    app.get(
                        pageUrl,
                        headers = headers,
                        allowRedirects = true
                    )

                if (response.code !in 200..299) {

                    Log.d(
                        "SubDub",
                        "HTTP ${response.code}: $pageUrl"
                    )

                    continue
                }

                var html =
                    response.text

                /*
                 * Decode common JavaScript escaping.
                 */

                html =
                    html
                        .replace("\\/", "/")
                        .replace("\\u002F", "/")
                        .replace("\\u003A", ":")
                        .replace("&amp;", "&")
                        .replace("&quot;", "\"")

                /*
                 * Try direct HLS URL.
                 */

                val direct =
                    normalM3u8Regex
                        .find(html)
                        ?.value

                if (direct != null) {

                    Log.d(
                        "SubDub",
                        "FOUND direct m3u8: $direct"
                    )

                    return emitHls(
                        direct,
                        callback,
                        headers
                    )
                }

                /*
                 * Try Rumble CDN chunklist URL.
                 */

                val rumble =
                    rumbleRegex
                        .find(html)
                        ?.value

                if (rumble != null) {

                    Log.d(
                        "SubDub",
                        "FOUND Rumble HLS: $rumble"
                    )

                    return emitHls(
                        rumble,
                        callback,
                        headers
                    )
                }

                /*
                 * Try escaped Rumble URL.
                 */

                val escaped =
                    escapedRumbleRegex
                        .find(html)
                        ?.value
                        ?.replace("\\/", "/")

                if (escaped != null) {

                    Log.d(
                        "SubDub",
                        "FOUND escaped HLS: $escaped"
                    )

                    return emitHls(
                        escaped,
                        callback,
                        headers
                    )
                }

                /*
                 * Try quoted URL.
                 */

                val quoted =
                    quotedM3u8Regex
                        .find(html)
                        ?.groupValues
                        ?.getOrNull(1)

                if (quoted != null) {

                    Log.d(
                        "SubDub",
                        "FOUND quoted HLS: $quoted"
                    )

                    return emitHls(
                        quoted,
                        callback,
                        headers
                    )
                }

                /*
                 * Sometimes the page contains a JSON-escaped URL
                 * that is not caught above.
                 */

                val decoded =
                    decodePossibleUrl(html)

                if (decoded != null) {

                    Log.d(
                        "SubDub",
                        "FOUND decoded HLS: $decoded"
                    )

                    return emitHls(
                        decoded,
                        callback,
                        headers
                    )
                }

                /*
                 * Useful debugging information.
                 */

                Log.d(
                    "SubDub",
                    "No HLS URL found on $pageUrl (html=${html.length})"
                )

            } catch (e: Exception) {

                Log.d(
                    "SubDub",
                    "Player error $pageUrl: ${e.message}"
                )
            }
        }

        /*
         * If data itself is ever an HLS URL,
         * support it directly.
         */

        if (
            data.startsWith("http://", true) ||
            data.startsWith("https://", true)
        ) {

            if (
                data.contains(".m3u8", true) ||
                data.contains("r_file=chunklist.m3u8", true)
            ) {

                return emitHls(
                    data,
                    callback,
                    headers
                )
            }
        }

        Log.e(
            "SubDub",
            "No authorized HLS playlist found for $tmdbId"
        )

        return false
    }

    // ---------------------------------------------------------
    // EMIT HLS LINK
    // ---------------------------------------------------------

    private suspend fun emitHls(
        rawUrl: String,
        callback: (ExtractorLink) -> Unit,
        headers: Map<String, String>
    ): Boolean {

        try {

            var url =
                rawUrl
                    .trim()
                    .trim('"', '\'')

            /*
             * Decode percent-encoding only where safe.
             *
             * Do NOT decode the complete URL because query
             * parameters may legitimately contain encoded data.
             */

            if (
                url.contains("r_file=chunklist.m3u8", true)
            ) {

                url =
                    url.replace(
                        "r_file=chunklist.m3u8",
                        "r_file=chunklist.m3u8",
                        ignoreCase = true
                    )
            }

            /*
             * Remove HTML/JS escaping.
             */

            url =
                url
                    .replace("\\/", "/")
                    .replace("\\u0026", "&")
                    .replace("&amp;", "&")

            /*
             * Do not inject a hard-coded r_range.
             *
             * If the source already supplied a range, keep it because
             * it belongs to the authorized playlist request discovered
             * from the source.
             */

            Log.d(
                "SubDub",
                "Emitting HLS: $url"
            )

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "SubDub Anime HLS",
                    url = url,
                    type = ExtractorLinkType.M3U8
                ) {

                    this.referer =
                        "$playerBase/"

                    this.quality =
                        Qualities.Unknown.value

                    this.headers =
                        headers
                }
            )

            return true

        } catch (e: Exception) {

            Log.e(
                "SubDub",
                "emitHls failed: ${e.message}"
            )

            return false
        }
    }

    // ---------------------------------------------------------
    // EXTRA URL DETECTION
    // ---------------------------------------------------------

    private fun decodePossibleUrl(
        text: String
    ): String? {

        return try {

            val match =
                Regex(
                    """https?(?::|%3A)(?:/|%2F){2}[^"'<> ]+""",
                    RegexOption.IGNORE_CASE
                )
                    .find(text)
                    ?.value
                    ?: return null

            var url =
                match

            url =
                URLDecoder.decode(
                    url,
                    "UTF-8"
                )

            url =
                url
                    .replace("\\/", "/")
                    .replace("\\u002F", "/")

            if (
                url.contains(
                    "chunklist.m3u8",
                    true
                ) ||
                url.contains(
                    ".m3u8",
                    true
                )
            ) {
                url
            } else {
                null
            }

        } catch (_: Exception) {
            null
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
