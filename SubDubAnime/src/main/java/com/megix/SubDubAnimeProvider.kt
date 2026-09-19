package com.megix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

open class SubDubAnimeProvider : MainAPI() {

    override var mainUrl = "https://ok.solarpanelcleaning.cc"
    override var name = "CineJoy"

    override val hasMainPage = true
    override var lang = "hi"

    override val supportedTypes = setOf(
        TvType.Movie
    )

    private val testM3u8 =
        "https://ok.solarpanelcleaning.cc/playlist/_ob6P6lqGyaiEGeoUmDUtg.m3u8"

    override val mainPage = mainPageOf(
        "test" to "CineJoy Test"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val result = newMovieSearchResponse(
            "CineJoy HLS Test",
            "cinejoy-test",
            TvType.Movie
        ) {
            this.posterUrl = null
        }

        return newHomePageResponse(
            request.name,
            listOf(result)
        )
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        if (
            !"cinejoy hls test".contains(query, ignoreCase = true) &&
            !query.contains("cinejoy", ignoreCase = true) &&
            !query.contains("test", ignoreCase = true)
        ) {
            return emptyList()
        }

        return listOf(
            newMovieSearchResponse(
                "CineJoy HLS Test",
                "cinejoy-test",
                TvType.Movie
            )
        )
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        if (url != "cinejoy-test") {
            return null
        }

        val data = listOf(
            EpisodeLink(
                testM3u8
            )
        )

        return newMovieLoadResponse(
            "CineJoy HLS Test",
            url,
            TvType.Movie,
            data
        ) {
            this.plot = "CineJoy direct HLS playback test."
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val sources = parseJson<ArrayList<EpisodeLink>>(data)

        sources.forEach {
            callback(
                newExtractorLink(
                    source = "CineJoy",
                    name = "CineJoy HLS",
                    url = it.source,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.P1080.value
                    this.referer = "https://ok.solarpanelcleaning.cc/"
                }
            )
        }

        return true
    }

    data class EpisodeLink(
        val source: String
    )
}
