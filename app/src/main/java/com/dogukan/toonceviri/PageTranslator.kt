package com.dogukan.toonceviri

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.io.Closeable
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Bir sayfayı çevirir:
 * 1) OCR ile yazıları bulur (uzun webtoon şeritleri dilimlenerek okunur)
 * 2) Yakın yazı bloklarını tek balonda birleştirir
 * 3) Balondaki yazıyı arka plan rengiyle siler
 * 4) Çeviriyi balonun içine sığacak boyutta, ortalanmış olarak yazar
 */
class PageTranslator(private val src: String, tgt: String) : Closeable {

    private val recognizer: TextRecognizer = when (src) {
        TranslateLanguage.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        TranslateLanguage.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        TranslateLanguage.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val translator: Translator = Translation.getClient(
        TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(tgt).build()
    )

    private val cjk = src == TranslateLanguage.JAPANESE || src == TranslateLanguage.CHINESE
    private val sep = if (cjk) "" else " "
    // Türkçe'de I/ı ve İ/i dönüşümü için dile özel kurallar gerekir
    private val srcLocale: Locale = if (src == TranslateLanguage.TURKISH) Locale("tr") else Locale.ROOT

    /** Bu çevirmenle işlenen sayfalarda çevrilemeyen balon sayısı. */
    var failedBubbles = 0
        private set

    /** Dil modelini indirir (ilk seferde internet gerekir, sonra çevrimdışı çalışır). */
    suspend fun prepare() {
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
    }

    private class Block(val rect: Rect, val lines: List<Rect>, val text: String, val thick: Float, val vertical: Boolean)
    private class Bubble(val rect: Rect, val lines: List<Rect>, val text: String, val thick: Float, val vertical: Boolean)

    /** Sayfadaki balonları bulur ve çevirir (resme dokunmaz). */
    suspend fun analyze(bmp: Bitmap): List<BubbleData> {
        val bubbles = merge(detect(bmp))
        return bubbles.map { b ->
            // Başarısız olursa bir kez daha dene; yine olmazsa balon orijinal haliyle kalır ve sayılır
            val t = runCatching { translator.translate(b.text).await() }
                .recoverCatching { translator.translate(b.text).await() }
                .getOrNull()?.trim().orEmpty()
            if (t.isEmpty()) failedBubbles++
            BubbleData(lines = b.lines, thick = b.thick, vertical = b.vertical, text = b.text, translation = t)
        }
    }

    /** Sayfayı çevirir: balonları bulur, çevirir ve çizer. Çizilmiş resim + balon bilgisi döner. */
    suspend fun process(page: Bitmap): Pair<Bitmap, List<BubbleData>> {
        val bmp = if (page.isMutable && page.config == Bitmap.Config.ARGB_8888) page
        else page.copy(Bitmap.Config.ARGB_8888, true)
        val data = analyze(bmp)
        return BubbleRenderer.render(bmp, data) to data
    }

    // ---------------- 1) OCR ----------------

    private suspend fun detect(bmp: Bitmap): List<Block> {
        val out = mutableListOf<Block>()
        val tileH = max(1600, bmp.width * 2)
        val overlap = 400
        var y = 0
        while (true) {
            val h = min(tileH, bmp.height - y)
            val tile = if (y == 0 && h == bmp.height) bmp else Bitmap.createBitmap(bmp, 0, y, bmp.width, h)
            val result = recognizer.process(InputImage.fromBitmap(tile, 0)).await()
            if (tile !== bmp) tile.recycle()

            val last = y + h >= bmp.height
            // Dilimler üst üste biniyor; her blok yalnızca merkezinin düştüğü dilimden alınır
            val ownTop = if (y == 0) 0 else y + overlap / 2
            val ownBottom = if (last) bmp.height else y + h - overlap / 2

            for (tb in result.textBlocks) {
                val lineData = tb.lines.mapNotNull { l ->
                    l.boundingBox?.let { box -> Pair(Rect(box).apply { offset(0, y) }, l.text) }
                }
                if (lineData.isEmpty()) continue
                val rect = Rect(lineData[0].first)
                lineData.forEach { rect.union(it.first) }
                if (rect.centerY() < ownTop || rect.centerY() >= ownBottom) continue

                val vertical = cjk && lineData.count { it.first.height() > it.first.width() * 1.5f } > lineData.size / 2
                val sorted = if (vertical) lineData.sortedByDescending { it.first.right }
                else lineData.sortedBy { it.first.top }
                val text = sorted.joinToString(sep) { it.second.trim() }
                if (!meaningful(text)) continue // sayfa numarası, tek harfli efektler vb.

                val thick = sorted.map { min(it.first.width(), it.first.height()) }.average().toFloat()
                out += Block(rect, sorted.map { it.first }, text, thick, vertical)
            }
            if (last) break
            y += h - overlap
        }
        return out
    }

    // ---------------- 2) Blokları balonlarda birleştir ----------------

    private fun merge(blocks: List<Block>): List<Bubble> {
        val n = blocks.size
        val parent = IntArray(n) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            return r
        }
        for (i in 0 until n) for (j in i + 1 until n) {
            val a = blocks[i]
            val b = blocks[j]
            val ratio = max(a.thick, b.thick) / max(1f, min(a.thick, b.thick))
            if (ratio > 1.7f) continue
            val gap = (min(a.thick, b.thick) * 0.9f).toInt()
            val grown = Rect(a.rect).apply { inset(-gap, -gap) }
            if (Rect.intersects(grown, b.rect)) parent[find(i)] = find(j)
        }
        return (0 until n).groupBy { find(it) }.values.map { idx ->
            val m = idx.map { blocks[it] }
            val members = if (m.first().vertical) m.sortedByDescending { it.rect.right } else m.sortedBy { it.rect.top }
            val rect = Rect(members[0].rect)
            members.forEach { rect.union(it.rect) }
            Bubble(
                rect = rect,
                lines = members.flatMap { it.lines },
                text = tidy(members.joinToString(sep) { it.text }),
                thick = members.map { it.thick }.average().toFloat(),
                vertical = members.any { it.vertical },
            )
        }
    }

    /**
     * Gerçek konuşma mı, yoksa sayfa numarası / tek harflik ses efekti mi?
     * Japonca/Çince'de "嘘" ya da "あ!" gibi tek karakterli konuşmalar da geçerli sayılır;
     * yalnızca tek bir hece (çoğunlukla çizime ait ses efekti) atlanır.
     */
    private fun meaningful(text: String): Boolean {
        val letters = text.count { it.isLetter() }
        if (letters >= 2) return true
        if (!cjk || letters == 0) return false
        val c = text.first { it.isLetter() }
        val isKanji = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN
        val hasPunct = text.any { it in "!?！？…‥" }
        return isKanji || hasPunct
    }

    /** OCR metnini çeviriye hazırlar: satır sonu tirelerini birleştirir, TAMAMI BÜYÜK yazıyı normal yazıya çevirir. */
    private fun tidy(raw: String): String {
        var t = raw.replace(Regex("(\\p{L})- (\\p{L})"), "$1$2").replace(Regex("\\s+"), " ").trim()
        val letters = t.filter { it.isLetter() }
        val mostlyUpper = letters.length >= 4 && letters.count { it.isUpperCase() } > letters.length * 0.8
        if (mostlyUpper) {
            val sb = StringBuilder(t.lowercase(srcLocale))
            var cap = true
            for (i in sb.indices) {
                val c = sb[i]
                if (cap && c.isLetter()) {
                    val up = c.toString().uppercase(srcLocale)
                    if (up.length == 1) sb.setCharAt(i, up[0])
                    cap = false
                }
                if (c == '.' || c == '!' || c == '?') cap = true
            }
            t = sb.toString()
            if (src == TranslateLanguage.ENGLISH) t = t.replace(Regex("\\bi\\b"), "I")
        }
        return t
    }

    override fun close() {
        recognizer.close()
        translator.close()
    }
}
