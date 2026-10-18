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
    private class Plan(val bubble: Bubble, val bg: Int, val area: Rect, val text: String)

    suspend fun process(page: Bitmap): Bitmap {
        val bmp = if (page.isMutable && page.config == Bitmap.Config.ARGB_8888) page
        else page.copy(Bitmap.Config.ARGB_8888, true)

        val bubbles = merge(detect(bmp))
        if (bubbles.isEmpty()) return bmp

        val translations = bubbles.map { b ->
            // Başarısız olursa bir kez daha dene; yine olmazsa balon orijinal haliyle kalır ve sayılır
            val t = runCatching { translator.translate(b.text).await() }
                .recoverCatching { translator.translate(b.text).await() }
                .getOrNull()?.trim().orEmpty()
            if (t.isEmpty()) failedBubbles++
            t
        }

        val canvas = Canvas(bmp)
        // Önce hepsini sil, sonra yaz: bir balonun yazısı diğerinin silmesiyle bozulmasın
        val plans = bubbles.indices.mapNotNull { i ->
            if (translations[i].isEmpty()) null else erase(bmp, canvas, bubbles[i], translations[i])
        }
        plans.forEach { draw(canvas, it) }
        return bmp
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

    // ---------------- 3) Yazıyı sil ----------------

    private fun erase(bmp: Bitmap, canvas: Canvas, b: Bubble, text: String): Plan {
        val pad = (b.thick * 0.2f).toInt() + 3
        val padded = b.lines.map { Rect(it).apply { inset(-pad, -pad); clampTo(bmp) } }
        val union = Rect(padded[0])
        padded.forEach { union.union(it) }
        val ring = Rect(union).apply { inset(-3, -3); clampTo(bmp) }

        val (bg, uniform) = borderColor(bmp, ring)
        val paint = Paint().apply { color = bg; style = Paint.Style.FILL }

        val area = if (uniform >= 0.8f) {
            // Düz renkli balon: bütün yazı alanını boya, sonra balonun içinde büyüyebildiği kadar büyüt
            canvas.drawRect(union, paint)
            val grown = grow(bmp, union, bg, (b.thick * 3).toInt(), (b.thick * 3).toInt())
            // Dikey (Japonca/Çince) balonlar dar ve uzun: yatay yazıya yer açmak için yanlara daha çok genişlet
            if (b.vertical || grown.width() < grown.height() * 0.6f) {
                grow(bmp, grown, bg, (b.thick * 10).toInt(), 0)
            } else grown
        } else {
            // Resmin üstündeki yazı: yalnızca satırları boya, çizimin geri kalanına dokunma
            padded.forEach { canvas.drawRect(it, paint) }
            Rect(union)
        }
        return Plan(b, bg, area, text)
    }

    /** Kenarlardaki piksellerden en yaygın rengi (balon rengi) ve kenarın ne kadar düz renk olduğunu bulur. */
    private fun borderColor(bmp: Bitmap, r: Rect): Pair<Int, Float> {
        val samples = ArrayList<Int>()
        var x = r.left
        while (x < r.right) {
            samples += bmp.getPixel(x, r.top)
            samples += bmp.getPixel(x, r.bottom - 1)
            x += 2
        }
        var y = r.top
        while (y < r.bottom) {
            samples += bmp.getPixel(r.left, y)
            samples += bmp.getPixel(r.right - 1, y)
            y += 2
        }
        if (samples.isEmpty()) return Pair(Color.WHITE, 0f)

        val bins = HashMap<Int, IntArray>()
        for (c in samples) {
            val key = ((Color.red(c) shr 5) shl 6) or ((Color.green(c) shr 5) shl 3) or (Color.blue(c) shr 5)
            val acc = bins.getOrPut(key) { IntArray(4) }
            acc[0] += Color.red(c)
            acc[1] += Color.green(c)
            acc[2] += Color.blue(c)
            acc[3]++
        }
        val best = bins.values.maxByOrNull { it[3] }!!
        val bg = Color.rgb(best[0] / best[3], best[1] / best[3], best[2] / best[3])
        val uniform = samples.count { similar(it, bg) }.toFloat() / samples.size
        return Pair(bg, uniform)
    }

    /** Dikdörtgeni, kenarları balon renginde kaldıkça dışa doğru büyütür → Türkçe uzun metne yer açılır. */
    private fun grow(bmp: Bitmap, start: Rect, bg: Int, maxGrowX: Int, maxGrowY: Int): Rect {
        val out = Rect(start)
        fun colOk(x: Int): Boolean {
            var ok = 0
            var tot = 0
            var y = out.top
            while (y < out.bottom) {
                tot++
                if (similar(bmp.getPixel(x, y), bg)) ok++
                y += 2
            }
            return tot > 0 && ok >= tot * 0.95f
        }
        fun rowOk(y: Int): Boolean {
            var ok = 0
            var tot = 0
            var x = out.left
            while (x < out.right) {
                tot++
                if (similar(bmp.getPixel(x, y), bg)) ok++
                x += 2
            }
            return tot > 0 && ok >= tot * 0.95f
        }
        var g = 0
        while (g < max(maxGrowX, maxGrowY)) {
            var grew = false
            if (g < maxGrowX) {
                if (out.left - 1 >= 0 && colOk(out.left - 1)) { out.left--; grew = true }
                if (out.right < bmp.width && colOk(out.right)) { out.right++; grew = true }
            }
            if (g < maxGrowY) {
                if (out.top - 1 >= 0 && rowOk(out.top - 1)) { out.top--; grew = true }
                if (out.bottom < bmp.height && rowOk(out.bottom)) { out.bottom++; grew = true }
            }
            if (!grew) break
            g++
        }
        return out
    }

    // ---------------- 4) Çeviriyi yaz ----------------

    private fun draw(canvas: Canvas, p: Plan) {
        val area = Rect(p.area)
        area.inset((area.width() * 0.06f).toInt(), (area.height() * 0.06f).toInt())
        val w = max(area.width(), 24)
        val dark = luminance(p.bg) < 140

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            color = if (dark) Color.WHITE else Color.BLACK
        }
        val base = p.bubble.thick * 0.85f
        val maxSize = max(12f, base * 1.15f)
        val minSize = max(9f, base * 0.4f)

        var size = maxSize
        var layout = build(p.text, paint, w, size)
        while (size > minSize && !fits(layout, paint, p.text, w, area.height())) {
            size *= 0.92f
            layout = build(p.text, paint, w, size)
        }

        // Okunabilirlik için balon renginde ince bir çerçeve (resim üstündeki yazılarda işe yarar)
        val halo = TextPaint(paint).apply {
            style = Paint.Style.STROKE
            strokeWidth = size * 0.15f
            strokeJoin = Paint.Join.ROUND
            color = p.bg
        }
        val haloLayout = build(p.text, halo, w, size)

        canvas.save()
        canvas.translate(
            area.left + (area.width() - w) / 2f,
            area.top + (area.height() - layout.height) / 2f,
        )
        haloLayout.draw(canvas)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun build(text: String, paint: TextPaint, width: Int, size: Float): StaticLayout {
        paint.textSize = size
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1f)
            .setIncludePad(false)
            .build()
    }

    private fun fits(layout: StaticLayout, paint: TextPaint, text: String, w: Int, h: Int): Boolean {
        if (layout.height > h) return false
        // Kelimeler ortadan bölünmesin
        return text.split(' ').all { paint.measureText(it) <= w }
    }

    // ---------------- yardımcılar ----------------

    private fun similar(a: Int, b: Int) =
        abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b)) < 70

    private fun luminance(c: Int) = 0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)

    private fun Rect.clampTo(b: Bitmap) {
        left = left.coerceIn(0, b.width - 1)
        top = top.coerceIn(0, b.height - 1)
        right = right.coerceIn(left + 1, b.width)
        bottom = bottom.coerceIn(top + 1, b.height)
    }

    override fun close() {
        recognizer.close()
        translator.close()
    }
}
