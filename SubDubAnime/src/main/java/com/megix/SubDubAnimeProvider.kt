package com.megix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.api.Log
import com.fasterxml.jackson.annotation.JsonProperty

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

    private val apiUrl     = "https://blakiteapi.xyz/api/getAllAnime.php"
    private val playerBase = "https://blakiteapi.xyz/"
    private val ua         = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"

    override val mainPage = mainPageOf(
        "all"     to "All Anime",
        "movies"  to "Movies",
        "series"  to "Series",
        "hindi"   to "Hindi Dubbed",
        "fandub"  to "Hindi Fan Dub",
        "engsub"  to "English Subbed"
    )

    // ──────────────────────────── main page / search ────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = fetchAll()
        val allItems = (data.movies.values + data.series.values).toList()
        val filtered = when (request.data) {
            "movies" -> data.movies.values.toList()
            "series" -> data.series.values.toList()
            "hindi"  -> allItems.filter { it.language?.contains("Hindi", true) == true }
            "fandub" -> allItems.filter {
                it.language?.contains("Fan", true) == true ||
                it.language?.contains("Fandub", true) == true
            }
            "engsub" -> allItems.filter { it.language?.contains("English Subbed", true) == true }
            else     -> allItems
        }
        return newHomePageResponse(request.name, filtered.mapNotNull { it.toSearchResponse() })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val data = fetchAll()
        val all = (data.movies.values + data.series.values).toList()
        return all
            .filter { it.title?.contains(query, true) == true }
            .mapNotNull { it.toSearchResponse() }
    }

    private suspend fun fetchAll(): AnimeData {
        return try {
            val json = app.get(apiUrl, headers = mapOf(
                "User-Agent" to ua,
                "Accept"     to "application/json, text/plain, */*",
                "Referer"    to "$playerBase/"
            )).text
            tryParseJson<ApiResponse>(json)?.data ?: AnimeData(emptyMap(), emptyMap())
        } catch (e: Exception) {
            Log.e("SubDub", "fetchAll failed: ${e.message}")
            AnimeData(emptyMap(), emptyMap())
        }
    }

    private fun AnimeItem.toSearchResponse(): SearchResponse? {
        val id = tmdbId ?: return null
        val t  = title   ?: return null
        return if (type?.equals("Series", true) == true) {
            newTvSeriesSearchResponse(t, id, TvType.TvSeries) { this.posterUrl = images?.poster }
        } else {
            newMovieSearchResponse(t, id, TvType.Movie) { this.posterUrl = images?.poster }
        }
    }

    // ──────────────────────────── load ────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val data = fetchAll()
        val item = data.movies[url]
            ?: data.series[url]
            ?: (data.movies.values + data.series.values).firstOrNull { it.tmdbId == url }
            ?: return null

        val title  = item.title ?: "Unknown"
        val poster = item.images?.poster
        val plot   = item.tmdbData?.synopsis
        val year   = item.tmdbData?.releaseDate?.substringBefore("-")?.toIntOrNull()
        val rating = item.tmdbData?.rating?.toDoubleOrNull()
        val genres = item.tmdbData?.genres ?: emptyList()
        val tmdbId = item.tmdbId ?: url
        val isSeries = item.type?.equals("Series", true) == true

        return if (isSeries) {
            val episodes = mutableListOf<Episode>()
            val seasons  = item.seasons ?: emptyMap()
            seasons.forEach { (key, info) ->
                val sNum  = info.seasonNumber ?: key.toIntOrNull() ?: 1
                val total = info.totalEpisodes ?: 0
                for (ep in 1..total) {
                    episodes.add(newEpisode("$tmdbId|$sNum|$ep|series") {
                        this.name      = "S$sNum E$ep"
                        this.season    = sNum
                        this.episode   = ep
                        this.posterUrl = poster
                    })
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot      = plot
                this.year      = year
                this.tags      = genres
                this.score     = Score.from10(rating)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, "$tmdbId|1|1|movie") {
                this.posterUrl = poster
                this.plot      = plot
                this.year      = year
                this.tags      = genres
                this.score     = Score.from10(rating)
            }
        }
    }

    // ──────────────────────────── loadLinks ────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        val tmdbId  = parts.getOrNull(0) ?: return false
        val season  = parts.getOrNull(1)?.toIntOrNull() ?: 1
        val episode = parts.getOrNull(2)?.toIntOrNull() ?: 1
        val type    = parts.getOrNull(3) ?: "movie"

        Log.d("SubDub", "loadLinks: tmdb=$tmdbId s=$season e=$episode type=$type")

        val headers = mapOf(
            "User-Agent" to ua,
            "Referer"    to "$playerBase/",
            "Origin"     to playerBase.trimEnd('/'),
            "Accept"     to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        )

        // list of URLs to try — first one that yields an m3u8 wins
        val candidates = mutableListOf<String>()

        // A — likely direct player endpoints on blakiteapi
        candidates += "$playerBase/watch/$tmdbId/$season/$episode"
        candidates += "$playerBase/player/$tmdbId/$season/$episode"
        candidates += "$playerBase/embed/$tmdbId/$season/$episode"
        candidates += "$playerBase/?id=$tmdbId&s=$season&e=$episode"
        candidates += "$playerBase/play/$tmdbId/$season/$episode"

        // B — type-specific variants
        if (type == "series") {
            candidates += "$playerBase/watch/$tmdbId?s=$season&e=$episode"
            candidates += "$playerBase/watch/$tmdbId-$season-$episode"
        } else {
            candidates += "$playerBase/watch/$tmdbId"
            candidates += "$playerBase/movie/$tmdbId"
        }

        // C — the site front (may redirect to player)
        candidates += "$mainUrl/$tmdbId"
        candidates += "$mainUrl/watch/$tmdbId/$season/$episode"

        val m3u8Regex = Regex("""https?://[^"'\s\\]+\.m3u8[^"'\s\\]*""")
        val chunkRegex = Regex("""https?://[^"'\s\\]+chunklist\.m3u8[^"'\s\\]*""")
        val rumbleRegex = Regex("""https?://[^"'\s\\]+\.tar\?r_file=chunklist\.m3u8[^"'\s\\]*""")

        for (url in candidates) {
            try {
                Log.d("SubDub", "trying: $url")
                val resp = app.get(url, headers = headers, allowRedirects = true)
                if (resp.code !in 200..299) {
                    Log.d("SubDub", "  → HTTP ${resp.code}, skip")
                    continue
                }
                val html = resp.text

                // priority: rumble chunklist > generic m3u8 > chunklist
                val hit = rumbleRegex.find(html)?.value
                    ?: chunkRegex.find(html)?.value
                    ?: m3u8Regex.find(html)?.value

                if (hit != null) {
                    Log.d("SubDub", "  → FOUND m3u8: $hit")

                    // strip any r_range — it's per-session and breaks playback
                    val clean = hit.replace(Regex("&r_range=[^&]*"), "")

                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name   = this.name,
                            url    = clean,
                            type   = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "$playerBase/"
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return true
                } else {
                    Log.d("SubDub", "  → no m3u8 in body (len=${html.length})")
                }
            } catch (e: Exception) {
                Log.d("SubDub", "  → error: ${e.message}")
            }
        }

        Log.e("SubDub", "no path produced an m3u8 — paste this log")
        return false
    }

    // ──────────────────────────── data classes ────────────────────────────

    data class ApiResponse(
        val success: Boolean? = null,
        val data: AnimeData = AnimeData(emptyMap(), emptyMap())
    )

    data class AnimeData(
        val movies: Map<String, AnimeItem> = emptyMap(),
        val series: Map<String, AnimeItem> = emptyMap()
    )

    data class AnimeItem(
        val tmdbId: String? = null,
        @JsonProperty("originalTmdbId") val originalTmdbId: String? = null,
        val title: String? = null,
        val language: String? = null,
        val type: String? = null,
        val status: String? = null,
        @JsonProperty("TMDB_DATA") val tmdbData: TmdbData? = null,
        @JsonProperty("IMAGES")    val images: Images? = null,
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
