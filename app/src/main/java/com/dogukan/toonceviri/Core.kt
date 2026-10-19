package com.dogukan.toonceviri

import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.util.Collections

data class WorkState(val title: String, val done: Int, val total: Int, val status: String)

/** İndirilmekte olan ya da kurulmaya hazır güncelleme. */
data class UpdateState(val info: UpdateInfo, val progress: Float, val file: File?)

data class UiState(
    val chapters: List<Chapter> = emptyList(),
    val work: WorkState? = null,
    /** Şu an çevrilen bölümün kimliği */
    val activeId: String? = null,
    /** Sırada bekleyen işlerin başlıkları */
    val queued: List<String> = emptyList(),
    /** Şu an düzenleme işlemi süren bölümler (düğmeler bu sırada kapalı) */
    val editBusy: Set<String> = emptySet(),
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

enum class TaskKind { LINK, GALLERY, ARCHIVE, RESUME, RETRANSLATE }

/** Sıradaki bir çeviri işi. */
class WorkTask(
    val title: String,
    val kind: TaskKind,
    val chapterId: String? = null,
    val cleanup: () -> Unit = {},
    val block: suspend () -> Unit,
)

/** Bir sayfa: kaynaktaki sırası (sayfa numarası olarak kullanılır) ve resmi yükleyen fonksiyon. */
class PageSource(val index: Int, val load: () -> Bitmap?)

/** Sayfa internetten indirilemedi (bölüm yarım kalır, sonra devam ettirilebilir). */
class DownloadFailed : Exception("Sayfa indirilemedi")

/**
 * Uygulama süreci boyunca yaşayan ortak durum.
 * Çeviri işleri ekrana (ViewModel'e) değil buraya bağlıdır: uygulama penceresi kapansa bile,
 * arka plan bildirimi açık olduğu sürece çeviri sürer.
 */
object Core {
    val state = MutableStateFlow(UiState())
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val lock = Any()
    val queue = ArrayDeque<WorkTask>()
    var runnerActive = false
    @Volatile var current: Job? = null
    @Volatile var updateJob: Job? = null
    @Volatile var lastAutoCheck = 0L

    /** Düzenlenen ama CBZ'si henüz güncellenmemiş bölümler */
    val cbzDirty: MutableSet<String> = Collections.synchronizedSet(HashSet())

    private val locks = HashMap<String, Mutex>()

    /** Aynı bölüm üzerinde iki işlem aynı anda çalışmasın diye bölüm başına kilit. */
    fun lockFor(id: String): Mutex = synchronized(locks) { locks.getOrPut(id) { Mutex() } }
}
