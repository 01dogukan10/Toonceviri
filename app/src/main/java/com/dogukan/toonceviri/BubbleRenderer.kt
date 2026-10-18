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
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max

/**
 * Bir sayfadaki tek bir balon: yazı satırlarının konumu, okunan metin ve çevirisi.
 * Sayfa başına JSON olarak saklanır; çeviri elle düzeltilince sayfa orijinalinden yeniden çizilir.
 */
data class BubbleData(
    val lines: List<Rect>,
    val thick: Float,
    val vertical: Boolean,
    val text: String,
    val translation: String,
) {
    val rect: Rect
        get() = Rect(lines[0]).also { r -> lines.forEach { r.union(it) } }

    fun toJson(): JSONObject = JSONObject()
        .put("lines", JSONArray().also { a ->
            lines.forEach { r -> a.put(JSONArray(listOf(r.left, r.top, r.right, r.bottom))) }
        })
        .put("thick", thick.toDouble())
        .put("vertical", vertical)
        .put("text", text)
        .put("translation", translation)

    companion object {
        fun fromJson(j: JSONObject): BubbleData? = runCatching {
            val a = j.getJSONArray("lines")
            val lines = List(a.length()) { i ->
                val r = a.getJSONArray(i)
                Rect(r.getInt(0), r.getInt(1), r.getInt(2), r.getInt(3))
            }
            if (lines.isEmpty()) return null
            BubbleData(
                lines = lines,
                thick = j.getDouble("thick").toFloat(),
                vertical = j.optBoolean("vertical"),
                text = j.optString("text"),
                translation = j.optString("translation"),
            )
        }.getOrNull()

        fun listToJson(list: List<BubbleData>, width: Int, height: Int): String = JSONObject()
            .put("w", width)
            .put("h", height)
            .put("bubbles", JSONArray().also { a -> list.forEach { a.put(it.toJson()) } })
            .toString()

        fun listFromJson(s: String): List<BubbleData> = runCatching {
            val a = JSONObject(s).getJSONArray("bubbles")
            List(a.length()) { fromJson(a.getJSONObject(it)) }.filterNotNull()
        }.getOrDefault(emptyList())
    }
}

/** Balonlardaki orijinal yazıyı siler ve çeviriyi balonun içine yazar. */
object BubbleRenderer {

    private class Plan(val bubble: BubbleData, val bg: Int, val area: Rect, val text: String)

    /** [page] üzerine çizer ve aynı (değiştirilebilir) resmi döndürür. Çevirisi boş balonlara dokunulmaz. */
    fun render(page: Bitmap, bubbles: List<BubbleData>): Bitmap {
        val bmp = if (page.isMutable && page.config == Bitmap.Config.ARGB_8888) page
        else page.copy(Bitmap.Config.ARGB_8888, true)
        if (bubbles.isEmpty()) return bmp
        val canvas = Canvas(bmp)
        // Önce hepsini sil, sonra yaz: bir balonun yazısı diğerinin silmesiyle bozulmasın
        val plans = bubbles.mapNotNull { b ->
            val t = b.translation.trim()
            if (t.isEmpty()) null else erase(bmp, canvas, b, t)
        }
        plans.forEach { draw(canvas, it) }
        return bmp
    }

    // ---------------- Yazıyı sil ----------------

    private fun erase(bmp: Bitmap, canvas: Canvas, b: BubbleData, text: String): Plan {
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

    /** Dikdörtgeni, kenarları balon renginde kaldıkça dışa doğru büyütür → uzun çeviriye yer açılır. */
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

    // ---------------- Çeviriyi yaz ----------------

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
}
