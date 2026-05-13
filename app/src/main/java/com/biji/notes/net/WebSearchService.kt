package com.biji.notes.net

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.dankito.readability4j.Readability4J
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

data class SearchResult(
    val title: String,
    val url: String,
    val snippet: String,
    val source: String
)

data class ExtractedArticle(
    val url: String,
    val title: String?,
    val byline: String?,
    val excerpt: String?,
    val textContent: String,
    val siteName: String?
)

/**
 * Tiny home-rolled web-search + page-reader. Uses the same OkHttp client as
 * the chat completions, no API keys. Adapts to multiple search engines so
 * a single endpoint going down doesn't take the feature with it.
 */
class WebSearchService {

    private val ua =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun search(query: String, limit: Int = 6): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext emptyList()

            // Try DuckDuckGo HTML first; fall back to Bing, then SoGou.
            val attempts = listOf(
                ::searchDuckDuckGo,
                ::searchBing,
                ::searchSogou
            )
            for (attempt in attempts) {
                val out = runCatching { attempt(q, limit) }.getOrNull()
                if (!out.isNullOrEmpty()) return@withContext out
            }
            emptyList()
        }

    // ---- DuckDuckGo HTML --------------------------------------------------

    private fun searchDuckDuckGo(query: String, limit: Int): List<SearchResult> {
        val url = "https://html.duckduckgo.com/html/?q=" +
            Uri.encode(query) +
            "&kl=wt-wt"
        val doc = fetchHtml(url, referer = "https://duckduckgo.com/") ?: return emptyList()
        return doc.select("div.result, div.web-result, div.results_links_deep")
            .mapNotNull { div ->
                val a = div.selectFirst("a.result__a, h2 a") ?: return@mapNotNull null
                val href = a.attr("href").let { unwrapDdgRedirect(it) }
                val title = a.text().trim()
                if (title.isBlank() || href.isBlank()) return@mapNotNull null
                val snippet = div.selectFirst("a.result__snippet, .result__snippet, .snippet")
                    ?.text()?.trim().orEmpty()
                SearchResult(
                    title = title,
                    url = href,
                    snippet = snippet,
                    source = "DuckDuckGo"
                )
            }
            .distinctBy { it.url }
            .take(limit)
    }

    private fun unwrapDdgRedirect(raw: String): String {
        val abs = when {
            raw.startsWith("http") -> raw
            raw.startsWith("//") -> "https:$raw"
            else -> return raw
        }
        return try {
            val uri = Uri.parse(abs)
            uri.getQueryParameter("uddg")?.let { URLDecoder.decode(it, "UTF-8") } ?: abs
        } catch (_: Throwable) { abs }
    }

    // ---- Bing -------------------------------------------------------------

    private fun searchBing(query: String, limit: Int): List<SearchResult> {
        val url = "https://cn.bing.com/search?q=" + Uri.encode(query) + "&mkt=zh-CN"
        val doc = fetchHtml(url) ?: return emptyList()
        return doc.select("li.b_algo")
            .mapNotNull { li ->
                val a = li.selectFirst("h2 a") ?: return@mapNotNull null
                val title = a.text().trim()
                val href = a.attr("href").trim()
                if (title.isBlank() || href.isBlank()) return@mapNotNull null
                val snippet = li.selectFirst("p, .b_caption p, .b_lineclamp4")
                    ?.text()?.trim().orEmpty()
                SearchResult(title, href, snippet, "Bing")
            }
            .distinctBy { it.url }
            .take(limit)
    }

    // ---- SoGou ------------------------------------------------------------

    private fun searchSogou(query: String, limit: Int): List<SearchResult> {
        val url = "https://www.sogou.com/web?query=" + Uri.encode(query)
        val doc = fetchHtml(url) ?: return emptyList()
        return doc.select("div.vrwrap, div.results .rb, .results > div")
            .mapNotNull { div ->
                val a = div.selectFirst("h3 a, .vr-title a, a.r-cards-link")
                    ?: return@mapNotNull null
                val title = a.text().trim()
                var href = a.attr("href").trim()
                if (href.startsWith("/link?")) href = "https://www.sogou.com$href"
                if (title.isBlank() || href.isBlank()) return@mapNotNull null
                val snippet = div.selectFirst(".star-wiki, .ft, .str_info, .fz-mid")
                    ?.text()?.trim().orEmpty()
                SearchResult(title, href, snippet, "SoGou")
            }
            .distinctBy { it.url }
            .take(limit)
    }

    // ---- Page reader (Readability) ----------------------------------------

    suspend fun read(url: String): ExtractedArticle? = withContext(Dispatchers.IO) {
        val html = fetchRawHtml(url) ?: return@withContext null
        val readability = Readability4J(url, html)
        val article = readability.parse()
        val text = article.textContent?.trim().orEmpty()
        ExtractedArticle(
            url = url,
            title = article.title,
            byline = article.byline,
            excerpt = article.excerpt,
            textContent = if (text.length > MAX_TEXT) text.take(MAX_TEXT) + "…" else text,
            siteName = runCatching { Uri.parse(url).host }.getOrNull()
        )
    }

    // ---- HTTP helpers -----------------------------------------------------

    private fun fetchHtml(url: String, referer: String? = null): Document? =
        fetchRawHtml(url, referer)?.let { Jsoup.parse(it, url) }

    private fun fetchRawHtml(url: String, referer: String? = null): String? = try {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.5")
            .apply { if (referer != null) header("Referer", referer) }
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null
            else resp.body?.string()
        }
    } catch (_: Throwable) { null }

    companion object {
        private const val MAX_TEXT = 6000
    }
}
