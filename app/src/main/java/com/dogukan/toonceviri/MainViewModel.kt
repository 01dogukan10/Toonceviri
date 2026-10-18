package com.dogukan.toonceviri

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.nl.translate.TranslateLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class WorkState(val title: String, val done: Int, val total: Int, val status: String)

data class UiState(
    val chapters: List<Chapter> = emptyList(),
    val work: WorkState? = null,
    val message: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val library = Library(File(app.filesDir, "chapters"))
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    /** Tarayıcıdan "Paylaş" ile gelen link. */
    val sharedUrl = MutableStateFlow<String?>(null)

    private var job: Job? = null

    var srcLang: String
        get() = prefs.getString("src", TranslateLanguage.ENGLISH) ?: TranslateLanguage.ENGLISH
        set(v) = prefs.edit().putString("src", v).apply()

    var tgtLang: String
        get() = prefs.getString("tgt", TranslateLanguage.TURKISH) ?: TranslateLanguage.TURKISH
        set(v) = prefs.edit().putString("tgt", v).apply()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = library.list()
            _state.update { it.copy(chapters = list) }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }
    private fun msg(text: String) = _state.update { it.copy(message = text) }
    private fun progress(title: String, done: Int, total: Int, status: String) =
        _state.update { it.copy(work = WorkState(title, done, total, status)) }

    fun delete(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            library.delete(id)
            refresh()
        }
    }

    fun cancel() {
        job?.cancel()
    }

    // ---------------- Linkten çeviri ----------------

    fun translateUrl(url: String, title: String) {
        launchWork(title.ifBlank { url }) {
            val fetcher = ChapterFetcher(http)
            progress(title.ifBlank { url }, 0, 0, "Bölüm sayfası okunuyor…")
            val page = fetcher.fetch(url)
            if (page.images.isEmpty()) {
                throw IllegalStateException(
                    "Sayfada resim bulunamadı. Site resimleri JavaScript ile yüklüyor olabilir; galeriden seçmeyi dene."
                )
            }
            val loaders = page.images.map { img -> { fetcher.download(img, url)?.let { decode(it) } } }
            runChapter(title.ifBlank { page.title }, url, loaders)
        }
    }

    // ---------------- Galeriden çeviri ----------------

    fun translateUris(uris: List<Uri>, title: String) {
        val name = title.ifBlank {
            "Galeri " + SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("tr")).format(Date())
        }
        val resolver = getApplication<Application>().contentResolver
        launchWork(name) {
            val loaders = uris.map { u ->
                { resolver.openInputStream(u)?.use { it.readBytes() }?.let { decode(it) } }
            }
            runChapter(name, "galeri", loaders)
        }
    }

    // ---------------- Ortak iş akışı ----------------

    private fun launchWork(title: String, block: suspend () -> Unit) {
        if (job?.isActive == true) {
            msg("Zaten bir çeviri sürüyor, bitmesini bekle ya da iptal et.")
            return
        }
        progress(title, 0, 0, "Başlıyor…")
        job = viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: CancellationException) {
                msg("İptal edildi")
                throw e
            } catch (e: Exception) {
                msg("Hata: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                _state.update { it.copy(work = null) }
                refresh()
            }
        }
    }

    private suspend fun runChapter(name: String, source: String, loaders: List<() -> Bitmap?>) {
        val src = srcLang
        val tgt = tgtLang
        val total = loaders.size
        val now = System.currentTimeMillis()
        var ch = Chapter(
            id = now.toString(36),
            title = name,
            source = source,
            src = src,
            tgt = tgt,
            createdAt = now,
            complete = false,
            original = emptyList(),
            translated = emptyList(),
        )
        library.save(ch)
        refresh()

        PageTranslator(src, tgt).use { pt ->
            progress(name, 0, total, "Dil modeli hazırlanıyor (ilk seferde ~30 MB iner)…")
            pt.prepare()

            loaders.forEachIndexed { i, load ->
                currentCoroutineContext().ensureActive()
                progress(name, i, total, "Sayfa ${i + 1}/$total indiriliyor…")
                val bmp = runCatching { load() }.getOrNull() ?: return@forEachIndexed
                // Küçük resimler (ikon, reklam vb.) atlanır
                if (bmp.width < 300 || bmp.height < 300) {
                    bmp.recycle()
                    return@forEachIndexed
                }
                val orig = library.savePieces(ch.id, "o", i, bmp)

                progress(name, i, total, "Sayfa ${i + 1}/$total çevriliyor…")
                val out = pt.process(bmp)
                val tr = library.savePieces(ch.id, "t", i, out)
                if (out !== bmp) out.recycle()
                bmp.recycle()

                ch = ch.copy(original = ch.original + orig, translated = ch.translated + tr)
                library.save(ch)
                refresh()
            }
        }

        if (ch.translated.isEmpty()) {
            library.delete(ch.id)
            throw IllegalStateException("Hiçbir sayfa çevrilemedi")
        }
        library.save(ch.copy(complete = true))
        msg("\"$name\" hazır (${ch.pageCount} sayfa)")
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        // Çok geniş resimleri küçült: hem hız hem bellek için
        if (b.width <= 1200) return b
        val h = (b.height * 1200f / b.width).toInt()
        val s = Bitmap.createScaledBitmap(b, 1200, h, true)
        if (s !== b) b.recycle()
        return s
    }
}
