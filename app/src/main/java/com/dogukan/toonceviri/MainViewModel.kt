package com.dogukan.toonceviri

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.nl.translate.TranslateLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class WorkState(val title: String, val done: Int, val total: Int, val status: String)

/** İndirilmekte olan ya da kurulmaya hazır güncelleme. */
data class UpdateState(val info: UpdateInfo, val progress: Float, val file: File?)

data class UiState(
    val chapters: List<Chapter> = emptyList(),
    val work: WorkState? = null,
    /** Şu an çevrilen bölümün kimliği */
    val activeId: String? = null,
    val message: String? = null,
    val models: List<ModelStatus> = emptyList(),
    /** Şu an indirilen / silinen dil kodları */
    val modelBusy: Set<String> = emptySet(),
    val update: UpdateState? = null,
    /** "Güncelleme hazır" penceresi açık mı */
    val updateDialog: Boolean = false,
    /** Güncellemeden sonra gösterilecek not: sürüm → değişiklikler */
    val whatsNew: Pair<String, String>? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val library = Library(File(app.filesDir, "chapters"))
    val settings = AppSettings(app)
    private val models = ModelManager(app)
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    /** Tarayıcıdan "Paylaş" ile gelen link. */
    val sharedUrl = MutableStateFlow<String?>(null)

    /** Yeni bölüm ekranında yazılanlar; başka ekrana gidip dönünce kaybolmasın diye burada tutulur. */
    var draftUrl by mutableStateOf("")
    var draftTitle by mutableStateOf("")

    private var job: Job? = null
    private var updateJob: Job? = null
    val updater = Updater(app, http)

    var srcLang: String
        get() = prefs.getString("src", TranslateLanguage.ENGLISH) ?: TranslateLanguage.ENGLISH
        set(v) = prefs.edit().putString("src", v).apply()

    var tgtLang: String
        get() = prefs.getString("tgt", TranslateLanguage.TURKISH) ?: TranslateLanguage.TURKISH
        set(v) = prefs.edit().putString("tgt", v).apply()

    init {
        refresh()
        refreshModels()
        showWhatsNewIfUpdated()
        checkUpdates(manual = false)
    }

    // ---------------- Otomatik güncelleme ----------------

    /** GitHub'da yeni sürüm varsa APK'yı arka planda indirir, bitince "Güncelleme hazır" penceresini açar. */
    private var lastAutoCheck = 0L

    fun checkUpdates(manual: Boolean) {
        if (!manual && !settings.autoUpdate.value) return
        // Uygulamaya her dönüşte denetlenir ama en fazla saatte bir
        if (!manual) {
            val now = System.currentTimeMillis()
            if (now - lastAutoCheck < 60 * 60 * 1000L && _state.value.update == null) return
            lastAutoCheck = now
        }
        if (updateJob?.isActive == true) {
            if (manual) msg("Güncelleme zaten indiriliyor")
            return
        }
        // Güncelleme zaten indirildiyse: elle denetlemede pencereyi tekrar aç, otomatikte rahatsız etme
        if (_state.value.update?.file != null) {
            if (manual) _state.update { it.copy(updateDialog = true) }
            return
        }
        updateJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val info = updater.checkLatest()
                if (info == null) {
                    if (manual) msg("En güncel sürümü kullanıyorsun (${updater.currentVersion})")
                    return@launch
                }
                settings.savePendingNotes(info.version, info.notes)
                _state.update { it.copy(update = UpdateState(info, 0f, null)) }
                var lastPct = -1
                val file = updater.download(info) { p ->
                    val pct = (p * 100).toInt()
                    if (pct != lastPct) {
                        lastPct = pct
                        _state.update { s -> s.copy(update = s.update?.copy(progress = p)) }
                    }
                }
                _state.update { it.copy(update = UpdateState(info, 1f, file), updateDialog = true) }
            } catch (e: Exception) {
                // İnternet yoksa otomatik denetim sessizce vazgeçer
                _state.update { s -> if (s.update?.file == null) s.copy(update = null) else s }
                if (manual) msg("Güncelleme denetlenemedi: ${e.message ?: "bağlantı yok"}")
            }
        }
    }

    fun dismissUpdateDialog() = _state.update { it.copy(updateDialog = false) }

    /** Android'in kurulum ekranını açar (ilk seferde "bilinmeyen uygulamalar" izni istenir). */
    fun installUpdate() {
        val file = _state.value.update?.file ?: return
        val ctx = getApplication<Application>()
        _state.update { it.copy(updateDialog = false) }
        if (!updater.canInstall()) {
            msg("Bu uygulamaya kurulum izni ver, sonra tekrar Güncelle'ye bas")
            ctx.startActivity(updater.permissionIntent())
            return
        }
        ctx.startActivity(updater.installIntent(file))
    }

    private fun showWhatsNewIfUpdated() {
        val current = updater.currentVersion
        val last = settings.lastVersion
        settings.lastVersion = current
        // İlk kurulumda gösterme; 0.12'den gelenlerde "lastVersion" henüz yok ama kurulum tamamlanmış
        val updated = if (last == null) settings.setupDone.value else last != current
        if (!updated) return
        viewModelScope.launch(Dispatchers.IO) {
            val notes = settings.pendingNotes(current) ?: updater.notesFor(current) ?: return@launch
            _state.update { it.copy(whatsNew = current to notes) }
        }
    }

    fun dismissWhatsNew() = _state.update { it.copy(whatsNew = null) }

    // ---------------- Dil modelleri ----------------

    fun refreshModels() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = runCatching { models.statuses() }.getOrNull() ?: return@launch
            _state.update { it.copy(models = list) }
        }
    }

    fun downloadModel(code: String, quiet: Boolean = false) {
        if (code in _state.value.modelBusy) return
        _state.update { it.copy(modelBusy = it.modelBusy + code) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                models.download(code)
                if (!quiet) msg("${Langs.name(code)} indirildi")
            } catch (e: Exception) {
                msg("${Langs.name(code)} indirilemedi: ${e.message ?: "bağlantı hatası"}")
            } finally {
                _state.update { it.copy(modelBusy = it.modelBusy - code) }
                refreshModels()
            }
        }
    }

    fun deleteModel(code: String) {
        if (code in _state.value.modelBusy) return
        _state.update { it.copy(modelBusy = it.modelBusy + code) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                models.delete(code)
                msg("${Langs.name(code)} silindi")
            } catch (e: Exception) {
                msg("Silinemedi: ${e.message}")
            } finally {
                _state.update { it.copy(modelBusy = it.modelBusy - code) }
                refreshModels()
            }
        }
    }

    // ---------------- Kayıt klasörü / CBZ ----------------

    fun setFolder(uri: Uri) {
        val cr = getApplication<Application>().contentResolver
        runCatching {
            cr.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        settings.setFolder(uri.toString())
    }

    /** CBZ'yi yazar, dosya adını bölüme kaydeder. Klasör seçilmemişse null döner. */
    private fun saveCbz(c: Chapter): String? {
        val folder = settings.folder.value ?: return null
        val name = library.exportCbz(getApplication<Application>(), c, Uri.parse(folder))
        if (name != c.cbz) library.save(c.copy(cbz = name))
        return name
    }

    /** Var olan bir bölümü elle CBZ olarak kaydet. */
    fun exportChapter(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val c = library.load(id) ?: return@launch
            if (settings.folder.value == null) {
                msg("Önce Ayarlar'dan kayıt klasörünü seç")
                return@launch
            }
            try {
                msg("Kaydedildi: ${saveCbz(c)}")
            } catch (e: Exception) {
                msg("CBZ kaydedilemedi: ${e.message}")
            }
            refresh()
        }
    }

    // ---------------- Kütüphane ----------------

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = library.list()
            _state.update { it.copy(chapters = list) }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }
    fun toast(text: String) = msg(text)
    private fun msg(text: String) = _state.update { it.copy(message = text) }
    private fun progress(title: String, done: Int, total: Int, status: String) =
        _state.update { it.copy(work = WorkState(title, done, total, status)) }

    /** Çevrilmekte olan bölüm silinirse önce çeviri durdurulur. */
    fun delete(id: String) {
        viewModelScope.launch {
            if (_state.value.activeId == id) job?.cancelAndJoin()
            withContext(Dispatchers.IO) { library.delete(id) }
            refresh()
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /** Yarım kalan (galeriden gelen) bölümü olduğu haliyle tamamlanmış say. */
    fun markComplete(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val c = library.load(id)?.takeIf { it.translated.isNotEmpty() } ?: return@launch
            val done = c.copy(complete = true)
            library.save(done)
            runCatching { saveCbz(done) }
            refresh()
        }
    }

    // ---------------- Linkten çeviri ----------------

    fun translateUrl(url: String, title: String) {
        launchWork(title.ifBlank { url }) {
            val (pageTitle, loaders) = fetchChapter(url, title.ifBlank { url })
            runChapter(title.ifBlank { pageTitle }, url, loaders, existing = null)
        }
    }

    /** Linkten gelen yarım bölümü kaldığı yerden devam ettir. */
    fun resume(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val c = library.load(id)?.takeIf { it.resumable } ?: return@launch
            launchWork(c.title) {
                val (_, loaders) = fetchChapter(c.source, c.title)
                runChapter(c.title, c.source, loaders, existing = c)
            }
        }
    }

    /**
     * Bölüm sayfasındaki resimleri önce geçici klasöre indirir, sonra bölüme ait olmayanları ayıklar:
     * sayfaların çoğundan belirgin şekilde dar olan resimler (öneri kapakları, küçük görseller) atlanır.
     * Çift sayfa gibi daha geniş sayfalar korunur.
     */
    private suspend fun fetchChapter(url: String, label: String): Pair<String, List<() -> Bitmap?>> {
        val fetcher = ChapterFetcher(http)
        progress(label, 0, 0, "Bölüm sayfası okunuyor…")
        val page = fetcher.fetch(url)
        if (page.images.isEmpty()) {
            throw IllegalStateException(
                "Sayfada resim bulunamadı. Site resimleri JavaScript ile yüklüyor olabilir; galeriden seçmeyi dene."
            )
        }

        val tmp = File(getApplication<Application>().cacheDir, "fetch").apply {
            deleteRecursively()
            mkdirs()
        }
        val files = mutableListOf<File>()
        page.images.forEachIndexed { i, img ->
            currentCoroutineContext().ensureActive()
            progress(label, i, page.images.size, "Resimler indiriliyor ${i + 1}/${page.images.size}…")
            val bytes = fetcher.download(img, url) ?: return@forEachIndexed
            val f = File(tmp, String.format(Locale.US, "%04d", i))
            f.writeBytes(bytes)
            files += f
        }

        val sized = files.map { f ->
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            Triple(f, o.outWidth, o.outHeight)
        }.filter { (_, w, h) -> w >= 300 && h >= 300 }

        val keep = if (sized.size >= 3) {
            val widths = sized.map { it.second }
            val main = widths.maxBy { w -> widths.count { kotlin.math.abs(it - w) <= w * 0.03 } }
            sized.filter { it.second >= main * 0.6 }
        } else sized

        if (keep.isEmpty()) throw IllegalStateException("Sayfadaki resimler indirilemedi")
        val loaders: List<() -> Bitmap?> = keep.map { (f, _, _) -> { decode(f.readBytes()) } }
        return page.title to loaders
    }

    // ---------------- Galeriden çeviri ----------------

    fun translateUris(uris: List<Uri>, title: String) {
        val name = title.ifBlank {
            "Galeri " + SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("tr")).format(Date())
        }
        val resolver = getApplication<Application>().contentResolver
        launchWork(name) {
            // Dosya adlarına göre doğal sırala (2.jpg, 10.jpg'den önce gelir); ad okunamazsa seçim sırası korunur
            val names = uris.map { u ->
                runCatching {
                    resolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                }.getOrNull()
            }
            val ordered = if (names.all { it != null }) {
                uris.zip(names).sortedWith { a, b -> naturalCompare(a.second!!, b.second!!) }.map { it.first }
            } else uris
            val loaders = ordered.map { u ->
                { resolver.openInputStream(u)?.use { it.readBytes() }?.let { decode(it) } }
            }
            runChapter(name, "galeri", loaders, existing = null)
        }
    }

    private fun naturalCompare(a: String, b: String): Int {
        val re = Regex("\\d+|\\D+")
        val pa = re.findAll(a.lowercase(Locale.ROOT)).map { it.value }.toList()
        val pb = re.findAll(b.lowercase(Locale.ROOT)).map { it.value }.toList()
        for (i in 0 until minOf(pa.size, pb.size)) {
            val x = pa[i]
            val y = pb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) {
                (x.toBigInteger()).compareTo(y.toBigInteger())
            } else x.compareTo(y)
            if (c != 0) return c
        }
        return pa.size - pb.size
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
                msg("İptal edildi. Linkten gelen bölümü ⋮ menüsünden kaldığı yerden devam ettirebilirsin.")
                throw e
            } catch (e: Exception) {
                msg("Hata: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                File(getApplication<Application>().cacheDir, "fetch").deleteRecursively()
                _state.update { it.copy(work = null, activeId = null) }
                refresh()
            }
        }
    }

    private suspend fun runChapter(
        name: String,
        source: String,
        loaders: List<() -> Bitmap?>,
        existing: Chapter?,
    ) {
        val src = existing?.src ?: srcLang
        val tgt = existing?.tgt ?: tgtLang
        val missing = buildList {
            if (!models.isReady(src, asSource = true)) add(Langs.name(src))
            if (!models.isReady(tgt, asSource = false)) add(Langs.name(tgt))
        }
        if (missing.isNotEmpty()) {
            throw IllegalStateException(
                "Önce Ayarlar → Dil modelleri'nden indir: ${missing.joinToString()}"
            )
        }
        val total = loaders.size
        val now = System.currentTimeMillis()
        var ch = existing ?: Chapter(
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
        _state.update { it.copy(activeId = ch.id) }
        refresh()

        val done = ch.donePages
        var failed = 0
        PageTranslator(src, tgt).use { pt ->
            progress(name, 0, total, "Dil modeli hazırlanıyor…")
            pt.prepare()

            loaders.forEachIndexed { i, load ->
                currentCoroutineContext().ensureActive()
                if (i in done) return@forEachIndexed // devam ederken önceden çevrilen sayfalar atlanır
                progress(name, i, total, "Sayfa ${i + 1}/$total hazırlanıyor…")
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

                ch = ch.copy(
                    original = (ch.original + orig.map { it.first }).distinct().sorted(),
                    translated = (ch.translated + tr.map { it.first }).distinct().sorted(),
                    ratios = ch.ratios + orig + tr,
                )
                library.save(ch)
                refresh()
            }
            failed = pt.failedBubbles
        }

        if (ch.translated.isEmpty()) {
            library.delete(ch.id)
            throw IllegalStateException("Hiçbir sayfa çevrilemedi")
        }
        ch = ch.copy(complete = true)
        library.save(ch)

        // Seçilen klasöre CBZ olarak da kaydet
        val saved = if (settings.folder.value == null) null else {
            progress(name, total, total, "CBZ olarak kaydediliyor…")
            try {
                saveCbz(ch)
            } catch (e: Exception) {
                msg("Çeviri hazır ama CBZ kaydedilemedi: ${e.message}")
                return
            }
        }
        val parts = mutableListOf("\"$name\" hazır (${ch.pageCount} sayfa)")
        if (saved != null) parts += "$saved kaydedildi"
        if (failed > 0) parts += "$failed balon çevrilemedi"
        msg(parts.joinToString(" · "))
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
