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
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import com.google.mlkit.nl.translate.TranslateLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.math.roundToInt
import kotlin.math.sqrt

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val library = Library(File(app.filesDir, "chapters"))
    val settings = AppSettings(app)
    private val models = ModelManager(app)
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // Durum uygulama genelinde tutulur (Core): pencere kapanıp açılsa da çeviri ve ilerleme kaybolmaz
    private val _state = Core.state
    val state = _state.asStateFlow()

    /** Tarayıcıdan "Paylaş" ile gelen link. */
    val sharedUrl = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** Yeni bölüm ekranında yazılanlar; başka ekrana gidip dönünce kaybolmasın diye burada tutulur. */
    var draftUrl by mutableStateOf("")
    var draftTitle by mutableStateOf("")

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
        Core.scope.launch { runCatching { updater.cleanup() } }
        checkUpdates(manual = false)
    }

    // ---------------- Otomatik güncelleme ----------------

    /** GitHub'da yeni sürüm varsa APK'yı arka planda indirir, bitince "Güncelleme hazır" penceresini açar. */
    fun checkUpdates(manual: Boolean) {
        if (!manual && !settings.autoUpdate.value) return
        if (!manual) {
            // "Sadece Wi‑Fi" açıksa mobil veride bekle (bir sonraki açılışta tekrar bakılır)
            if (settings.wifiOnly.value && updater.isMetered()) return
            // Uygulamaya her dönüşte denetlenir ama en fazla saatte bir
            val now = System.currentTimeMillis()
            if (now - Core.lastAutoCheck < 60 * 60 * 1000L && _state.value.update == null) return
            Core.lastAutoCheck = now
        }
        if (Core.updateJob?.isActive == true) {
            if (manual) msg("Güncelleme zaten indiriliyor")
            return
        }
        // Güncelleme zaten indirildiyse: elle denetlemede pencereyi tekrar aç, otomatikte rahatsız etme
        val ready = _state.value.update?.file
        if (ready != null) {
            if (ready.exists()) {
                if (manual) _state.update { it.copy(updateDialog = true) }
                return
            }
            // Dosya silinmiş (telefon önbelleği temizlemiş olabilir): yeniden indir
            _state.update { it.copy(update = null, updateDialog = false) }
        }
        Core.updateJob = Core.scope.launch {
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
        if (!file.exists()) {
            _state.update { it.copy(update = null) }
            msg("Güncelleme dosyası silinmiş, yeniden indiriliyor…")
            checkUpdates(manual = true)
            return
        }
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
        Core.scope.launch {
            // Atlanan sürümler varsa (ör. 0.13 → 0.15) hepsinin notu birlikte gösterilir
            val notes = settings.pendingNotes(current) ?: updater.notesBetween(last, current) ?: return@launch
            _state.update { it.copy(whatsNew = current to notes) }
        }
    }

    fun dismissWhatsNew() = _state.update { it.copy(whatsNew = null) }

    // ---------------- Dil modelleri ----------------

    fun refreshModels() {
        Core.scope.launch {
            val list = runCatching { models.statuses() }.getOrNull() ?: return@launch
            _state.update { it.copy(models = list) }
        }
    }

    fun downloadModel(code: String, quiet: Boolean = false) {
        if (code in _state.value.modelBusy) return
        _state.update { it.copy(modelBusy = it.modelBusy + code) }
        Core.scope.launch {
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
        Core.scope.launch {
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

    /** CBZ'yi yazar, dosya adını bölüme kaydeder. Klasör seçilmemişse null döner. Kilit tutulurken çağrılır. */
    private fun saveCbz(c: Chapter): String? {
        val folder = settings.folder.value ?: return null
        val name = library.exportCbz(getApplication<Application>(), c, Uri.parse(folder))
        if (name != c.cbz) library.save(c.copy(cbz = name))
        Core.cbzDirty.remove(c.id)
        return name
    }

    /** Var olan bir bölümü elle CBZ olarak kaydet. */
    fun exportChapter(id: String) {
        if (settings.folder.value == null) {
            msg("Önce Ayarlar'dan kayıt klasörünü seç")
            return
        }
        Core.scope.launch {
            Core.lockFor(id).withLock {
                val c = library.load(id) ?: return@withLock
                try {
                    msg("Kaydedildi: ${saveCbz(c)}")
                } catch (e: Exception) {
                    msg("CBZ kaydedilemedi: ${e.message}")
                }
            }
            refresh()
        }
    }

    // ---------------- Kütüphane ----------------

    fun refresh() {
        Core.scope.launch {
            val list = library.list()
            _state.update { it.copy(chapters = list) }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }
    fun toast(text: String) = msg(text)
    private fun msg(text: String) = _state.update { it.copy(message = text) }
    private fun progress(title: String, done: Int, total: Int, status: String) =
        _state.update { it.copy(work = WorkState(title, done, total, status)) }

    /** Bölümü siler. Çevriliyorsa önce durdurulur, sıradaki işleri de kaldırılır. */
    fun delete(id: String) {
        removeQueued { it.chapterId == id }
        Core.scope.launch {
            if (_state.value.activeId == id) Core.current?.cancelAndJoin()
            Core.lockFor(id).withLock {
                library.delete(id)
                Core.cbzDirty.remove(id)
            }
            refresh()
        }
    }

    /** Yarım kalan bölümü olduğu haliyle tamamlanmış say. */
    fun markComplete(id: String) {
        if (isActive(id)) return
        Core.scope.launch {
            Core.lockFor(id).withLock {
                val c = library.load(id)?.takeIf { it.translated.isNotEmpty() } ?: return@withLock
                val done = c.copy(complete = true)
                library.save(done)
                runCatching { saveCbz(done) }
            }
            refresh()
        }
    }

    // ---------------- Çeviri sırası ----------------

    /**
     * İşi sıraya ekler. Bir çeviri sürüyorsa reddedilmez, sırasını bekler.
     * İlk iş başlarken arka plan bildirimi açılır (Android uygulamayı kapatmasın diye).
     */
    private fun enqueue(task: WorkTask) {
        var start = false
        val position: Int
        synchronized(Core.lock) {
            Core.queue.addLast(task)
            position = Core.queue.size
            if (!Core.runnerActive) {
                Core.runnerActive = true
                start = true
            }
        }
        publishQueue()
        if (start) {
            progress(task.title, 0, 0, "Başlıyor…")
            startWorkService()
            Core.scope.launch { runQueue() }
        } else {
            msg("\"${task.title}\" sıraya eklendi ($position. sırada)")
        }
    }

    private suspend fun runQueue() {
        while (true) {
            val task = synchronized(Core.lock) {
                val t = Core.queue.removeFirstOrNull()
                if (t == null) {
                    Core.runnerActive = false
                    // Kilit içinde temizlenir: tam bu anda yeni iş gelirse onun durumu silinmesin
                    _state.update { it.copy(work = null, activeId = null) }
                }
                t
            } ?: break
            publishQueue()
            progress(task.title, 0, 0, "Başlıyor…")
            val job = Core.scope.launch { runTask(task) }
            Core.current = job
            job.join()
            Core.current = null
        }
        publishQueue()
    }

    private suspend fun runTask(task: WorkTask) {
        try {
            task.block()
        } catch (e: CancellationException) {
            msg(
                when (task.kind) {
                    TaskKind.LINK, TaskKind.RESUME ->
                        "İptal edildi. ⋮ menüsünden kaldığı yerden devam ettirebilirsin."
                    TaskKind.RETRANSLATE -> "Yeniden çeviri iptal edildi, bölüm değişmedi."
                    TaskKind.GALLERY, TaskKind.ARCHIVE ->
                        "İptal edildi. Çevrilen sayfaları ⋮ → Bu haliyle bitir ile saklayabilirsin."
                }
            )
        } catch (e: OutOfMemoryError) {
            msg("Bellek yetmedi: bu bölümdeki resimler çok büyük")
        } catch (e: Exception) {
            msg("Hata: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            File(getApplication<Application>().cacheDir, "fetch").deleteRecursively()
            runCatching { task.cleanup() }
            _state.update { it.copy(activeId = null) }
            refresh()
        }
    }

    private fun publishQueue() {
        val titles = synchronized(Core.lock) { Core.queue.map { it.title } }
        _state.update { it.copy(queued = titles) }
    }

    private fun removeQueued(predicate: (WorkTask) -> Boolean): Int {
        val removed = synchronized(Core.lock) {
            val r = Core.queue.filter(predicate)
            Core.queue.removeAll(r.toSet())
            r
        }
        removed.forEach { runCatching { it.cleanup() } }
        publishQueue()
        return removed.size
    }

    /** Şu anki çeviriyi durdurur; sıradakiler devam eder. */
    fun cancel() {
        Core.current?.cancel()
    }

    fun clearQueue() {
        val n = removeQueued { true }
        if (n > 0) msg("Sıradaki $n iş kaldırıldı")
    }

    private fun startWorkService() {
        val app = getApplication<Application>()
        runCatching { ContextCompat.startForegroundService(app, Intent(app, WorkService::class.java)) }
    }

    /** Bölüm şu an çevriliyorsa düzenlemeye izin verme. */
    private fun isActive(id: String): Boolean {
        if (_state.value.activeId == id) {
            msg("Bu bölüm şu an çevriliyor; bitince ya da iptal edince düzenleyebilirsin")
            return true
        }
        return false
    }

    // ---------------- Linkten çeviri ----------------

    fun translateUrl(url: String, title: String) {
        val label = title.ifBlank { url }
        enqueue(WorkTask(label, TaskKind.LINK) {
            val (pageTitle, pages) = fetchChapter(url, label)
            runChapter(title.ifBlank { pageTitle }, url, pages, existing = null)
        })
    }

    /** Linkten gelen yarım bölümü kaldığı yerden devam ettir. */
    fun resume(id: String) {
        val c = _state.value.chapters.firstOrNull { it.id == id }?.takeIf { it.resumable } ?: return
        if (_state.value.activeId == id) return
        if (synchronized(Core.lock) { Core.queue.any { it.chapterId == id } }) {
            msg("Bu bölüm zaten sırada")
            return
        }
        enqueue(WorkTask(c.title, TaskKind.RESUME, chapterId = id, block = task@{
            val fresh = library.load(id)?.takeIf { it.resumable } ?: return@task
            val (_, pages) = fetchChapter(fresh.source, fresh.title)
            runChapter(fresh.title, fresh.source, pages, existing = fresh)
        }))
    }

    /**
     * Bölüm sayfasındaki resimleri önce geçici klasöre indirir, sonra bölüme ait olmayanları ayıklar:
     * sayfaların çoğundan belirgin şekilde dar olan resimler (öneri kapakları, küçük görseller) atlanır.
     * İndirilemeyen resimler atlanmaz: o sayfa "indirilemedi" sayılır ve bölüm yarım kalır.
     * Sayfa numarası, resmin sayfadaki sırasıdır; böylece devam ettirirken numaralar kaymaz.
     */
    private suspend fun fetchChapter(url: String, label: String): Pair<String, List<PageSource>> {
        val fetcher = ChapterFetcher(http)
        progress(label, 0, 0, "Bölüm sayfası okunuyor…")
        val page = fetcher.fetch(url)
        if (page.images.isEmpty()) {
            throw IllegalStateException(
                "Sayfada resim bulunamadı. Site resimleri JavaScript ile yüklüyor olabilir; galeriden seçmeyi dene."
            )
        }
        if (page.images.size > 999) throw IllegalStateException("Bu sayfada çok fazla resim var (999'dan fazla)")

        val tmp = File(getApplication<Application>().cacheDir, "fetch").apply {
            deleteRecursively()
            mkdirs()
        }
        // index → indirilen dosya (null = indirilemedi)
        val files = ArrayList<Pair<Int, File?>>()
        page.images.forEachIndexed { i, img ->
            currentCoroutineContext().ensureActive()
            progress(label, i, page.images.size, "Resimler indiriliyor ${i + 1}/${page.images.size}…")
            val bytes = fetcher.download(img, url)
            if (bytes == null) {
                files += i to null
            } else {
                val f = File(tmp, String.format(Locale.US, "%04d", i))
                f.writeBytes(bytes)
                files += i to f
            }
        }
        if (files.all { it.second == null }) {
            throw IllegalStateException("Sayfadaki resimler indirilemedi. İnternet bağlantını kontrol et.")
        }

        class Sized(val index: Int, val file: File, val w: Int, val h: Int)
        val sized = files.mapNotNull { (i, f) ->
            if (f == null) return@mapNotNull null
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            Sized(i, f, o.outWidth, o.outHeight)
        }.filter { it.w >= 300 && it.h >= 300 }

        val keepIdx = if (sized.size >= 3) {
            val widths = sized.map { it.w }
            val main = widths.maxBy { w -> widths.count { kotlin.math.abs(it - w) <= w * 0.03 } }
            sized.filter { it.w >= main * 0.6 }
        } else sized
        val keepMap = keepIdx.associateBy { it.index }

        val sources = files.mapNotNull { (i, f) ->
            when {
                f == null -> PageSource(i) { throw DownloadFailed() }
                i in keepMap -> PageSource(i) { decode(f.readBytes()) }
                else -> null
            }
        }
        if (sources.isEmpty()) throw IllegalStateException("Sayfada bölüme ait resim bulunamadı")
        return page.title to sources
    }

    // ---------------- Galeriden çeviri ----------------

    fun translateUris(uris: List<Uri>, title: String) {
        val name = title.ifBlank {
            "Galeri " + SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("tr")).format(Date())
        }
        val resolver = getApplication<Application>().contentResolver
        // Sırada beklerken de okunabilsin diye izni sakla, iş bitince bırak
        uris.forEach { keepReadPermission(it) }
        enqueue(WorkTask(name, TaskKind.GALLERY, cleanup = { uris.forEach { releaseReadPermission(it) } }) {
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
            val pages = ordered.mapIndexed { i, u ->
                PageSource(i) { resolver.openInputStream(u)?.use { it.readBytes() }?.let { decode(it) } }
            }
            runChapter(name, "galeri", pages, existing = null)
        })
    }

    private fun keepReadPermission(uri: Uri) {
        runCatching {
            getApplication<Application>().contentResolver
                .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun releaseReadPermission(uri: Uri) {
        runCatching {
            getApplication<Application>().contentResolver
                .releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    // ---------------- Düzenleme ----------------

    /**
     * Düzenleme işlemini bölümün kilidiyle çalıştırır. İşlem sürerken aynı bölümdeki
     * yeni dokunuşlar yok sayılır (hızlı art arda basınca sayfalar karışmasın).
     */
    private fun edit(id: String, onDone: (() -> Unit)? = null, block: suspend () -> Unit) {
        // Yapılamıyorsa da "bitti" bildir: ekran beklemede kalmasın
        if (isActive(id) || id in _state.value.editBusy) {
            onDone?.invoke()
            return
        }
        _state.update { it.copy(editBusy = it.editBusy + id) }
        Core.scope.launch {
            try {
                Core.lockFor(id).withLock { block() }
            } catch (e: OutOfMemoryError) {
                msg("Bellek yetmedi: sayfa çok büyük")
            } catch (e: Exception) {
                msg("İşlem yapılamadı: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                _state.update { it.copy(editBusy = it.editBusy - id) }
                refresh()
                if (onDone != null) withContext(Dispatchers.Main) { onDone() }
            }
        }
    }

    /**
     * Başlık ya da dil değişikliğinden sonra kaydeder ve CBZ'yi hemen yeni adla yazar
     * (eski adlı CBZ silinir). Kilit tutulurken çağrılır.
     */
    private fun commitRename(before: Chapter, after: Chapter) {
        var c = after
        val folder = settings.folder.value
        if (folder != null && before.cbz != null && c.complete) {
            library.deleteCbz(getApplication<Application>(), Uri.parse(folder), before.cbz)
            c = c.copy(cbz = null)
            library.save(c)
            runCatching { saveCbz(c) }.onFailure { msg("CBZ güncellenemedi: ${it.message}") }
        } else {
            library.save(c)
        }
    }

    /** Sayfa sırası/silme/balon düzeltmesi: kaydet, CBZ'yi ekrandan çıkınca bir kez güncelle. */
    private fun commitPages(c: Chapter) {
        library.save(c)
        if (c.cbz != null) Core.cbzDirty.add(c.id)
    }

    /** Başlık ve kategoriyi kaydeder. */
    fun saveDetails(id: String, title: String, category: String) = edit(id) {
        val c = library.load(id) ?: return@edit
        val t = title.trim().ifBlank { c.title }
        val cat = category.trim().ifBlank { null }
        if (t == c.title && cat == c.category) return@edit
        val after = c.copy(title = t, category = cat)
        if (t != c.title) commitRename(c, after) else library.save(after)
        msg("Kaydedildi")
    }

    /** Sayfayı bir yukarı (-1) ya da aşağı (+1) taşır. */
    fun movePage(id: String, page: Int, delta: Int) = edit(id) {
        val c = library.load(id) ?: return@edit
        val order = c.pages.toMutableList()
        val from = order.indexOf(page)
        val to = from + delta
        if (from < 0 || to !in order.indices) return@edit
        order.add(to, order.removeAt(from))
        commitPages(library.reorderPages(c, order))
    }

    fun deletePage(id: String, page: Int) = edit(id) {
        val c = library.load(id) ?: return@edit
        if (c.pages.size <= 1) {
            msg("Son sayfa silinemez; bölümün tamamını silmek için kütüphaneyi kullan")
            return@edit
        }
        commitPages(library.reorderPages(c, c.pages - page))
        msg("Sayfa silindi")
    }

    suspend fun bubblesFor(id: String, page: Int): List<BubbleData>? =
        withContext(Dispatchers.IO) { library.loadBubbles(id, page) }

    /**
     * Bir balonun çevirisini değiştirir ve sayfayı orijinalinden yeniden çizer.
     * Boş metin = o balon çevrilmez, orijinal yazı kalır.
     */
    fun editBubble(id: String, page: Int, index: Int, newText: String, onDone: () -> Unit) =
        edit(id, onDone) {
            val c = library.load(id) ?: return@edit
            val list = library.loadBubbles(id, page) ?: return@edit
            if (index !in list.indices) return@edit
            val updated = list.toMutableList().also { it[index] = it[index].copy(translation = newText.trim()) }
            val orig = library.stitch(c, "o", page) ?: throw IllegalStateException("Orijinal sayfa bulunamadı")
            val w = orig.width
            val h = orig.height
            val out = BubbleRenderer.render(orig, updated)
            library.saveBubbles(id, page, updated, w, h)
            val c2 = library.replaceTranslated(c, page, out)
            out.recycle()
            commitPages(c2)
        }

    /** Düzenleme ekranlarından çıkınca: bir şey değiştiyse CBZ'yi bir kez güncelle. */
    fun syncCbz(id: String) {
        if (id !in Core.cbzDirty) return
        Core.scope.launch {
            Core.lockFor(id).withLock {
                if (id !in Core.cbzDirty) return@withLock
                val c = library.load(id) ?: return@withLock
                if (c.complete && c.cbz != null && settings.folder.value != null) {
                    runCatching { saveCbz(c) }.onFailure { msg("CBZ güncellenemedi: ${it.message}") }
                } else {
                    Core.cbzDirty.remove(id)
                }
            }
        }
    }

    /**
     * Bölümü başka dillerle baştan çevirir (orijinal sayfalar saklı olduğu için internet gerekmez).
     * Yeni çeviri ayrı bir klasöre yazılır, ancak tamamı bitince eskisinin yerine geçer:
     * yarıda iptal edilirse bölüm hiç değişmemiş olur.
     */
    fun retranslate(id: String, src: String, tgt: String) {
        if (isActive(id)) return
        if (synchronized(Core.lock) { Core.queue.any { it.chapterId == id } }) {
            msg("Bu bölüm zaten sırada")
            return
        }
        if (src == tgt) {
            msg("Kaynak ve hedef dil aynı olamaz")
            return
        }
        val title = _state.value.chapters.firstOrNull { it.id == id }?.title ?: "Bölüm"
        enqueue(WorkTask(title, TaskKind.RETRANSLATE, chapterId = id, cleanup = { library.clearStaging(id) }, block = task@{
            val missing = buildList {
                if (!models.isReady(src, asSource = true)) add(Langs.name(src))
                if (!models.isReady(tgt, asSource = false)) add(Langs.name(tgt))
            }
            if (missing.isNotEmpty()) {
                throw IllegalStateException("Önce Ayarlar → Dil modelleri'nden indir: ${missing.joinToString()}")
            }
            Core.lockFor(id).withLock {
                val before = library.load(id) ?: return@task
                _state.update { it.copy(activeId = id) }
                library.clearStaging(id)
                val st = library.staging(id)
                val pages = before.pages
                val ratios = HashMap<String, Float>()
                var failed = 0
                PageTranslator(src, tgt).use { pt ->
                    progress(before.title, 0, pages.size, "Dil modeli hazırlanıyor…")
                    pt.prepare()
                    pages.forEachIndexed { n, p ->
                        currentCoroutineContext().ensureActive()
                        progress(before.title, n, pages.size, "Sayfa ${n + 1}/${pages.size} yeniden çevriliyor…")
                        val orig = library.stitch(before, "o", p) ?: return@forEachIndexed
                        val w = orig.width
                        val h = orig.height
                        val (out, bubbles) = pt.process(orig)
                        library.saveBubblesIn(st, p, bubbles, w, h)
                        ratios += library.savePiecesIn(st, "t", p, out)
                        out.recycle()
                    }
                    failed = pt.failedBubbles
                }
                currentCoroutineContext().ensureActive()
                val after = library.applyStaging(before, src, tgt, ratios)
                if (before.tgt != tgt) commitRename(before, after) else commitPages(after)
                syncCbzNow(after.id)
                msg(
                    "\"${after.title}\" ${Langs.name(src)} → ${Langs.name(tgt)} yeniden çevrildi" +
                        if (failed > 0) " · $failed balon çevrilemedi" else ""
                )
            }
        }))
    }

    /** Kilit tutulurken CBZ'yi hemen güncelle (yalnızca değiştiyse). */
    private fun syncCbzNow(id: String) {
        if (id !in Core.cbzDirty) return
        val c = library.load(id) ?: return
        if (c.complete && c.cbz != null && settings.folder.value != null) runCatching { saveCbz(c) }
    }

    // ---------------- CBZ / ZIP dosyasından çeviri ----------------

    /**
     * Arşivdeki resimleri dosya adına göre doğal sırayla (2.jpg, 10.jpg'den önce) çevirir.
     * Sayfa sırası arşivdeki isimlere bağlı olduğu için galeriden seçmeye göre kayma olmaz.
     */
    fun translateArchive(uri: Uri, title: String) {
        val app = getApplication<Application>()
        val resolver = app.contentResolver
        val fileName = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        val name = title.ifBlank { fileName?.substringBeforeLast('.')?.trim()?.ifBlank { null } ?: "Arşiv" }
        keepReadPermission(uri)

        enqueue(WorkTask(name, TaskKind.ARCHIVE, cleanup = { releaseReadPermission(uri) }) {
            progress(name, 0, 0, "Dosya açılıyor…")
            // ZipFile rastgele erişim istediği için önce geçici bir kopya alınır
            val tmp = File(app.cacheDir, "fetch").apply {
                deleteRecursively()
                mkdirs()
            }
            val copy = File(tmp, "archive.zip")
            (resolver.openInputStream(uri) ?: throw IllegalStateException("Dosya açılamadı"))
                .use { input -> copy.outputStream().use { input.copyTo(it) } }

            openZip(copy).use { z ->
                val entries = z.entries().asSequence()
                    .filter { e ->
                        val n = e.name
                        val base = n.substringAfterLast('/')
                        !e.isDirectory &&
                            !n.startsWith("__MACOSX") && !base.startsWith(".") &&
                            IMAGE_EXT.any { base.lowercase(Locale.ROOT).endsWith(it) }
                    }
                    .sortedWith { a, b -> naturalCompare(a.name, b.name) }
                    .toList()
                if (entries.isEmpty()) throw IllegalStateException("Dosyanın içinde resim bulunamadı")
                if (entries.size > 999) throw IllegalStateException("Dosyada çok fazla resim var (999'dan fazla)")

                val pages = entries.mapIndexed { i, e ->
                    PageSource(i) { z.getInputStream(e).use { it.readBytes() }.let { decode(it) } }
                }
                runChapter(name, "arşiv", pages, existing = null)
            }
        })
    }

    /**
     * Önce UTF‑8 dener. Japonca/Çince adlı dosyalarla Windows'ta yapılan arşivlerde adlar farklı
     * kodlandığı için açılmazsa, adları bayt bayt okuyan yedek kodlamayla açar
     * (sıralama için gereken rakamlar ve uzantılar doğru kalır).
     */
    private fun openZip(f: File): ZipFile =
        try {
            ZipFile(f)
        } catch (e: Exception) {
            try {
                ZipFile(f, Charsets.ISO_8859_1)
            } catch (e2: Exception) {
                throw IllegalStateException("Bu dosya CBZ/ZIP değil ya da bozuk (CBR/RAR desteklenmiyor)")
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

    // ---------------- Bölüm çevirme ----------------

    private suspend fun runChapter(
        name: String,
        source: String,
        pages: List<PageSource>,
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
        val total = pages.size
        val now = System.currentTimeMillis()
        val id = existing?.id ?: now.toString(36)

        Core.lockFor(id).withLock {
            var ch = when {
                existing == null -> Chapter(
                    id = id,
                    title = name,
                    source = source,
                    src = src,
                    tgt = tgt,
                    createdAt = now,
                    complete = false,
                    original = emptyList(),
                    translated = emptyList(),
                    stableIndex = true,
                )
                // 0.16'dan önce başlamış yarım bölüm: sayfa numaraları farklı hesaplandığı için baştan çevir
                !existing.stableIndex -> library.clearPages(existing).copy(stableIndex = true)
                else -> existing
            }
            library.save(ch)
            _state.update { it.copy(activeId = ch.id) }
            refresh()

            val done = ch.donePages
            var failed = 0
            var notDownloaded = 0
            var unreadable = 0
            PageTranslator(src, tgt).use { pt ->
                progress(name, 0, total, "Dil modeli hazırlanıyor…")
                pt.prepare()

                pages.forEachIndexed { n, ps ->
                    currentCoroutineContext().ensureActive()
                    val i = ps.index
                    if (i in done) return@forEachIndexed // devam ederken önceden çevrilen sayfalar atlanır
                    progress(name, n, total, "Sayfa ${n + 1}/$total hazırlanıyor…")
                    val bmp = try {
                        ps.load()
                    } catch (e: DownloadFailed) {
                        notDownloaded++
                        return@forEachIndexed
                    } catch (e: OutOfMemoryError) {
                        unreadable++
                        return@forEachIndexed
                    } catch (e: Exception) {
                        unreadable++
                        return@forEachIndexed
                    }
                    if (bmp == null) {
                        unreadable++
                        return@forEachIndexed
                    }
                    // Küçük resimler (ikon, reklam vb.) atlanır
                    if (bmp.width < 300 || bmp.height < 300) {
                        bmp.recycle()
                        return@forEachIndexed
                    }
                    try {
                        val orig = library.savePieces(ch.id, "o", i, bmp)
                        progress(name, n, total, "Sayfa ${n + 1}/$total çevriliyor…")
                        val w = bmp.width
                        val h = bmp.height
                        val (out, bubbles) = pt.process(bmp)
                        // Balon bilgisi saklanır: çeviri sonradan elle düzeltilebilsin
                        library.saveBubbles(ch.id, i, bubbles, w, h)
                        val tr = library.savePieces(ch.id, "t", i, out)
                        if (out !== bmp) out.recycle()

                        ch = ch.copy(
                            original = (ch.original + orig.map { it.first }).distinct().sorted(),
                            translated = (ch.translated + tr.map { it.first }).distinct().sorted(),
                            ratios = ch.ratios + orig + tr,
                        )
                        library.save(ch)
                        refresh()
                    } catch (e: OutOfMemoryError) {
                        unreadable++
                    } finally {
                        bmp.recycle()
                    }
                }
                failed = pt.failedBubbles
            }

            if (ch.translated.isEmpty()) {
                library.delete(ch.id)
                throw IllegalStateException(
                    if (notDownloaded > 0) "Sayfalar indirilemedi. İnternet bağlantını kontrol et."
                    else "Hiçbir sayfa çevrilemedi"
                )
            }

            // İnemeyen sayfa varsa bölüm "yarım" kalır: sonra kaldığı yerden devam ettirilebilir
            if (notDownloaded > 0) {
                library.save(ch)
                msg(
                    "\"$name\": $notDownloaded sayfa indirilemedi, bölüm yarım kaldı. " +
                        "İnternet düzelince ⋮ → Kaldığı yerden devam et."
                )
                return
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
            if (unreadable > 0) parts += "$unreadable sayfa açılamadı"
            if (failed > 0) parts += "$failed balon çevrilemedi"
            msg(parts.joinToString(" · "))
        }
    }

    companion object {
        private val IMAGE_EXT = listOf(".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif", ".avif", ".heic")

        /** Bir sayfa en fazla bu kadar piksel olabilir (~80 MB); daha büyükse küçültülerek açılır. */
        private const val MAX_PIXELS = 20_000_000.0
    }

    /**
     * Resmi açar. Genişlik en fazla 1200 piksel; çok uzun resimler belleğe sığsın diye ayrıca küçültülür.
     * Büyük resim önce 2'nin katlarıyla küçültülerek okunur, böylece tam boyutu hiç belleğe alınmaz.
     */
    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w0 = bounds.outWidth
        val h0 = bounds.outHeight
        if (w0 <= 0 || h0 <= 0) return null

        var scale = minOf(1.0, 1200.0 / w0)
        val pixels = w0.toDouble() * h0 * scale * scale
        if (pixels > MAX_PIXELS) scale *= sqrt(MAX_PIXELS / pixels)
        val tw = (w0 * scale).roundToInt().coerceAtLeast(1)
        val th = (h0 * scale).roundToInt().coerceAtLeast(1)

        var sample = 1
        while (w0 / (sample * 2) >= tw && h0 / (sample * 2) >= th) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sample
        }
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        if (b.width == tw && b.height == th) return b
        val s = Bitmap.createScaledBitmap(b, tw, th, true)
        if (s !== b) b.recycle()
        return s
    }
}
