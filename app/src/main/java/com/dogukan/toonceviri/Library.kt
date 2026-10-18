package com.dogukan.toonceviri

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
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
) {
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
    fun savePieces(id: String, prefix: String, page: Int, bmp: Bitmap): List<String> {
        val names = mutableListOf<String>()
        val maxH = 3000
        var y = 0
        var part = 0
        while (y < bmp.height) {
            val h = min(maxH, bmp.height - y)
            val piece = if (y == 0 && h == bmp.height) bmp else Bitmap.createBitmap(bmp, 0, y, bmp.width, h)
            val name = String.format(Locale.US, "%s_%03d_%02d.jpg", prefix, page, part)
            FileOutputStream(File(dir(id), name)).use { piece.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            if (piece !== bmp) piece.recycle()
            names += name
            y += h
            part++
        }
        return names
    }

    companion object {
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
