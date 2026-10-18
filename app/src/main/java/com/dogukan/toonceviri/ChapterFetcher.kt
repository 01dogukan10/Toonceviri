package com.dogukan.toonceviri

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URL

/** Bölüm sayfasının HTML'ini indirir ve içindeki çizgi roman resimlerinin adreslerini bulur. */
class ChapterFetcher(private val client: OkHttpClient) {

    data class Result(val title: String, val images: List<String>)

    fun fetch(url: String): Result {
        val bytes = get(url, null)
        val doc = Jsoup.parse(ByteArrayInputStream(bytes), null, url)

        val all = LinkedHashSet<String>()
        val kept = LinkedHashSet<String>()
        for (img in doc.select("img")) {
            val candidates = mutableListOf<String>()
            // Tembel yükleme yapan sitelerde asıl adres genelde data-* içindedir, src ise yer tutucudur
            for (a in ATTRS) candidates += img.absUrl(a)
            val srcset = img.attr("data-srcset").ifBlank { img.attr("srcset") }
            if (srcset.isNotBlank()) {
                val last = srcset.split(",").last().trim().substringBefore(" ")
                resolve(url, last)?.let { candidates += it }
            }
            val pick = candidates.firstOrNull { it.startsWith("http") && !SKIP.containsMatchIn(it) } ?: continue
            all += pick
            if (!insideNoise(img)) kept += pick
        }
        // Gürültü filtresi her şeyi elerse (site alışılmadık isimler kullanıyorsa) filtresiz listeye dön
        val found = if (kept.isNotEmpty()) kept else all

        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: doc.title().ifBlank { url }
        return Result(title.trim(), found.toList())
    }

    fun download(url: String, referer: String?): ByteArray? = runCatching { get(url, referer) }.getOrNull()

    private fun get(url: String, referer: String?): ByteArray {
        val b = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Accept", "*/*")
        if (referer != null) b.header("Referer", referer)
        client.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            return r.body?.bytes() ?: throw IOException("Boş yanıt")
        }
    }

    /** Resim; başlık, menü, yorumlar, "önerilen seriler" gibi bölüm dışı bir alanın içinde mi? */
    private fun insideNoise(img: Element): Boolean {
        var e: Element? = img.parent()
        // <body> ve <html> sınıfları (ör. WordPress "has-sidebar") tüm sayfayı kapsadığı için bakılmaz
        while (e != null && e.tagName() != "body" && e.tagName() != "html") {
            if (e.tagName() in NOISE_TAGS) return true
            val key = e.id() + " " + e.className()
            if (NOISE.containsMatchIn(key)) return true
            e = e.parent()
        }
        return false
    }

    private fun resolve(base: String, rel: String): String? =
        runCatching { URL(URL(base), rel).toString() }.getOrNull()

    companion object {
        private const val UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Mobile Safari/537.36"
        private val ATTRS = listOf("data-src", "data-lazy-src", "data-original", "data-url", "data-lazyload", "src")
        private val NOISE_TAGS = setOf("header", "footer", "nav", "aside")
        private val NOISE = Regex(
            "related|recommend|suggest|popular|trending|comment|sidebar|footer|header|navbar|menu|widget|ads?[-_ ]|advert|sponsor",
            RegexOption.IGNORE_CASE,
        )
        private val SKIP = Regex("\\.(svg|gif)(\\?|$)|logo|avatar|favicon|banner|/ads?/", RegexOption.IGNORE_CASE)
    }
}
