package com.megix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class SubDubAnimeProvider : MainAPI() {
    override var mainUrl = "https://subdubanime.site"
    override var name = "SubDub Anime"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    override var lang = "hi"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home / Ongoing Anime"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data).document
        val home = document.select("div.item, div.anime-card, div.film-poster").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val title = this.selectFirst("a.dynamic-name, h3 a, .film-name")?.text() ?: return null
        val href = fixUrl(this.selectFirst("a")?.attr("href") ?: return null)
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src") ?: this.selectFirst("img")?.attr("src"))
        
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search?keyword=$query").document
        return document.select("div.item, div.film-poster").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h2.heading, h1")?.text() ?: "Anime"
        val poster = fixUrlNull(document.selectFirst(".m-i-poster img, .anime-banner img")?.attr("src"))
        val description = document.selectFirst(".description, .synopsis")?.text()
        
        val episodes = document.select("div.episodes-list a, .episode-item").map {
            val epHref = fixUrl(it.attr("href"))
            val epName = it.text()
            val epNum = Regex("""\d+""").find(epName)?.value?.toIntOrNull()
            newEpisode(epHref) { name = epName; episode = epNum }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            addEpisodes(DubStatus.Dubbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val iframeUrl = document.selectFirst("iframe")?.attr("src") ?: return false
        
        loadExtractor(iframeUrl, data, subtitleCallback, callback)
        return true
    }
}
