package com.dogukan.toonceviri

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.min

data class Chapter(
    val id: String,
    val title: String,
    val source: String,
    val src: String,
    val tgt: String,
    val createdAt: Long,
    val complete: Boolean,
    val original: List<String>,
    val translated: List<String>,
    /** Parça adı → en/boy oranı (okuyucu, resmi açmadan yer ayırabilsin diye) */
    val ratios: Map<String, Float> = emptyMap(),
    /** Seçilen klasöre yazılan CBZ dosyasının adı */
    val cbz: String? = null,
    /** Kullanıcının verdiği kategori (ör. seri adı); boşsa kategorisiz */
    val category: String? = null,
) {
    /** Sayfa numaraları sırayla (her sayfa birden çok parçadan oluşabilir). */
    val pages: List<Int>
        get() = translated.mapNotNull { Library.pageOf(it) }.distinct().sorted()

    /** Linkten gelen yarım bölümler kaldığı yerden devam ettirilebilir. */
    val resumable: Boolean get() = !complete && source.startsWith("http")

    /** Çevirisi biten sayfa numaraları (yarım bölümü devam ettirirken atlanır). */
    val donePages: Set<Int>
        get() = translated.filter { it.endsWith("_00.jpg") }
            .mapNotNull { it.removePrefix("t_").substringBefore("_").toIntOrNull() }
            .toSet()

    /** Uzun sayfalar parçalara bölündüğü için "_00" ile biten parçalar = sayfa sayısı. */
    val pageCount: Int get() = translated.count { it.endsWith("_00.jpg") }
}

/** Çevrilen bölümleri uygulamanın kendi klasöründe saklar: <id>/meta.json + resimler. */
class Library(private val root: File) {

    init {
        root.mkdirs()
    }

    fun dir(id: String) = File(root, id)
    fun file(id: String, name: String) = File(dir(id), name)

    fun list(): List<Chapter> =
        (root.listFiles() ?: emptyArray())
            .filter { it.isDirectory }
            .mapNotNull { load(it.name) }
            .sortedByDescending { it.createdAt }

    fun load(id: String): Chapter? = runCatching {
        val j = JSONObject(File(dir(id), "meta.json").readText())
        Chapter(
            id = id,
            title = j.getString("title"),
            source = j.optString("source"),
            src = j.getString("src"),
            tgt = j.getString("tgt"),
            createdAt = j.getLong("createdAt"),
            complete = j.optBoolean("complete"),
            original = j.optJSONArray("original").toStringList(),
            translated = j.optJSONArray("translated").toStringList(),
            ratios = j.optJSONObject("ratios")?.let { r ->
                r.keys().asSequence().associateWith { k -> r.optDouble(k, 0.7).toFloat() }
            } ?: emptyMap(),
            cbz = j.optString("cbz").ifBlank { null },
            category = j.optString("category").trim().ifBlank { null },
        )
    }.getOrNull()

    fun save(c: Chapter) {
        val d = dir(c.id)
        d.mkdirs()
        val j = JSONObject()
            .put("title", c.title)
            .put("source", c.source)
            .put("src", c.src)
            .put("tgt", c.tgt)
            .put("createdAt", c.createdAt)
            .put("complete", c.complete)
            .put("original", JSONArray(c.original))
            .put("translated", JSONArray(c.translated))
            .put("ratios", JSONObject().also { r -> c.ratios.forEach { (k, v) -> r.put(k, v.toDouble()) } })
            .put("cbz", c.cbz ?: "")
            .put("category", c.category ?: "")
        val tmp = File(d, "meta.json.tmp")
        tmp.writeText(j.toString())
        tmp.renameTo(File(d, "meta.json"))
    }

    fun delete(id: String) {
        dir(id).deleteRecursively()
    }

    /**
     * Resmi en fazla 3000 px yüksekliğinde parçalara bölüp JPEG olarak kaydeder.
     * Çok uzun webtoon şeritleri tek parça olursa telefon çizemiyor.
     */
    fun savePieces(id: String, prefix: String, page: Int, bmp: Bitmap): List<Pair<String, Float>> {
        dir(id).mkdirs()
        val names = mutableListOf<Pair<String, Float>>()
        val maxH = 3000
        var y = 0
        var part = 0
        while (y < bmp.height) {
            val h = min(maxH, bmp.height - y)
            val piece = if (y == 0 && h == bmp.height) bmp else Bitmap.createBitmap(bmp, 0, y, bmp.width, h)
            val name = String.format(Locale.US, "%s_%03d_%02d.jpg", prefix, page, part)
            FileOutputStream(File(dir(id), name)).use { piece.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            if (piece !== bmp) piece.recycle()
            names += name to (bmp.width.toFloat() / h)
            y += h
            part++
        }
        return names
    }

    /**
     * Çevrilen sayfaları kullanıcının seçtiği klasöre "<başlık> [TR].cbz" olarak yazar.
     * CBZ, sayfaların sırayla durduğu bir zip dosyasıdır; çoğu çizgi roman okuyucusu açar.
     */
    fun exportCbz(ctx: Context, c: Chapter, tree: Uri): String {
        val dir = DocumentFile.fromTreeUri(ctx, tree)
            ?.takeIf { it.canWrite() }
            ?: throw IllegalStateException("Kayıt klasörüne yazılamıyor, Ayarlar'dan klasörü yeniden seç")
        val safe = c.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(80).ifBlank { "Bolum" }
        val base = "$safe [${c.tgt.uppercase(Locale.ROOT)}]"
        // Bu bölüm daha önce kaydedildiyse aynı dosyanın üstüne yaz;
        // aynı isimde başka bir bölümün dosyası varsa "(2)", "(3)" ekle, onu silme.
        val name = if (c.cbz != null && c.cbz.startsWith(base)) c.cbz else {
            var n = "$base.cbz"
            var k = 2
            while (dir.findFile(n) != null) n = "$base (${k++}).cbz"
            n
        }
        dir.findFile(name)?.delete()
        val f = dir.createFile("application/octet-stream", name)
            ?: throw IllegalStateException("CBZ dosyası oluşturulamadı")
        val out = ctx.contentResolver.openOutputStream(f.uri)
            ?: throw IllegalStateException("CBZ dosyası açılamadı")
        ZipOutputStream(BufferedOutputStream(out)).use { zip ->
            // JPEG zaten sıkıştırılmış; tekrar sıkıştırmak sadece yavaşlatır
            zip.setLevel(0)
            c.translated.forEachIndexed { i, n ->
                zip.putNextEntry(ZipEntry(String.format(Locale.US, "%04d.jpg", i + 1)))
                file(c.id, n).inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return f.name ?: name
    }

    /** Seçilen klasördeki eski CBZ dosyasını siler (başlık ya da dil değişince yenisi yazılır). */
    fun deleteCbz(ctx: Context, tree: Uri, name: String) {
        runCatching { DocumentFile.fromTreeUri(ctx, tree)?.findFile(name)?.delete() }
    }

    // ---------------- Düzenleme ----------------

    private fun bubbleFile(id: String, page: Int) = File(dir(id), String.format(Locale.US, "b_%03d.json", page))

    fun saveBubbles(id: String, page: Int, bubbles: List<BubbleData>, width: Int, height: Int) {
        dir(id).mkdirs()
        bubbleFile(id, page).writeText(BubbleData.listToJson(bubbles, width, height))
    }

    /** Sayfanın balon bilgisi; 0.15'ten önce çevrilen sayfalarda yoktur (null). */
    fun loadBubbles(id: String, page: Int): List<BubbleData>? {
        val f = bubbleFile(id, page)
        return if (f.exists()) BubbleData.listFromJson(f.readText()) else null
    }

    fun pieces(c: Chapter, prefix: String, page: Int): List<String> {
        val list = if (prefix == "o") c.original else c.translated
        return list.filter { pageOf(it) == page }.sorted()
    }

    /** Bir sayfanın parçalarını alt alta birleştirip tek resim yapar. */
    fun stitch(c: Chapter, prefix: String, page: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val parts = pieces(c, prefix, page).mapNotNull { BitmapFactory.decodeFile(file(c.id, it).path, opts) }
        if (parts.isEmpty()) return null
        if (parts.size == 1) return parts[0]
        val out = Bitmap.createBitmap(parts[0].width, parts.sumOf { it.height }, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        var y = 0f
        parts.forEach {
            canvas.drawBitmap(it, 0f, y, null)
            y += it.height
            it.recycle()
        }
        return out
    }

    /** Bir sayfanın çevrilmiş parçalarını yenileriyle değiştirir. */
    fun replaceTranslated(c: Chapter, page: Int, bmp: Bitmap): Chapter {
        val old = pieces(c, "t", page)
        old.forEach { file(c.id, it).delete() }
        val fresh = savePieces(c.id, "t", page, bmp)
        return c.copy(
            translated = (c.translated - old.toSet() + fresh.map { it.first }).distinct().sorted(),
            ratios = (c.ratios - old.toSet()) + fresh,
        )
    }

    /**
     * Sayfaları verilen sıraya göre yeniden numaralar (0, 1, 2…). Listede olmayan sayfalar silinir.
     * Orijinal, çeviri ve balon dosyaları birlikte taşınır.
     */
    fun reorderPages(c: Chapter, order: List<Int>): Chapter {
        val d = dir(c.id)
        val renames = HashMap<String, String>()
        val files = d.listFiles()?.filter { pageOf(it.name) != null } ?: emptyList()
        // 1) Kalan sayfaları geçici adlara taşı (çakışma olmasın)
        order.forEachIndexed { newIdx, oldIdx ->
            files.filter { pageOf(it.name) == oldIdx }.forEach { f ->
                val nn = f.name.substring(0, 2) + String.format(Locale.US, "%03d", newIdx) + f.name.substring(5)
                f.renameTo(File(d, "tmp_$nn"))
                renames[f.name] = nn
            }
        }
        // 2) Listede olmayan (silinen) sayfaların dosyalarını sil
        d.listFiles()?.filter { pageOf(it.name) != null }?.forEach { it.delete() }
        // 3) Geçici adlardan son adlara
        d.listFiles()?.filter { it.name.startsWith("tmp_") }?.forEach { it.renameTo(File(d, it.name.removePrefix("tmp_"))) }

        return c.copy(
            original = c.original.mapNotNull { renames[it] }.sorted(),
            translated = c.translated.mapNotNull { renames[it] }.sorted(),
            ratios = c.ratios.mapNotNull { (k, v) -> renames[k]?.let { it to v } }.toMap(),
        )
    }

    companion object {
        private val PAGE_FILE = Regex("^[otb]_(\\d{3})[_.]")

        /** "t_004_01.jpg" → 4; sayfa dosyası değilse null */
        fun pageOf(name: String): Int? = PAGE_FILE.find(name)?.groupValues?.get(1)?.toIntOrNull()

        /** Okuyucuda resim yüklenmeden önce doğru yüksekliği ayırmak için en/boy oranı. */
        fun aspect(f: File): Float {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth.toFloat() / o.outHeight else 0.7f
        }
    }
}

private fun JSONArray?.toStringList(): List<String> =
    if (this == null) emptyList() else List(length()) { getString(it) }
