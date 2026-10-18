package com.dogukan.toonceviri

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val label: String) {
    SYSTEM("Sistem"),
    LIGHT("Açık"),
    DARK("Koyu"),
    AMOLED("AMOLED siyah"),
}

enum class ReaderBg(val label: String, val argb: Long) {
    BLACK("Siyah", 0xFF000000),
    GRAY("Koyu gri", 0xFF1E1E1E),
    WHITE("Beyaz", 0xFFFFFFFF),
}

object Accents {
    val list = listOf(
        0xFF5B3FE0 to "Mor",
        0xFF1E88E5 to "Mavi",
        0xFF00897B to "Turkuaz",
        0xFF43A047 to "Yeşil",
        0xFFF9A825 to "Sarı",
        0xFFF4511E to "Turuncu",
        0xFFE53935 to "Kırmızı",
        0xFFD81B60 to "Pembe",
        0xFF6D4C41 to "Kahve",
        0xFF546E7A to "Gri mavi",
    )
}

data class ThemeSettings(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val accent: Long = Accents.list[0].first,
    val dynamic: Boolean = false,
    val readerBg: ReaderBg = ReaderBg.BLACK,
)

/** Kalıcı uygulama ayarları: tema, kayıt klasörü, ilk kurulum. */
class AppSettings(ctx: Context) {

    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(readTheme())
    val theme = _theme.asStateFlow()

    private val _folder = MutableStateFlow(p.getString("folder", null))
    /** CBZ dosyalarının kaydedileceği klasörün adresi (SAF tree URI). */
    val folder = _folder.asStateFlow()

    private val _setupDone = MutableStateFlow(p.getBoolean("setupDone", false))
    val setupDone = _setupDone.asStateFlow()

    private fun readTheme() = ThemeSettings(
        mode = runCatching { ThemeMode.valueOf(p.getString("themeMode", null)!!) }.getOrDefault(ThemeMode.SYSTEM),
        accent = p.getLong("accent", Accents.list[0].first),
        dynamic = p.getBoolean("dynamic", false),
        readerBg = runCatching { ReaderBg.valueOf(p.getString("readerBg", null)!!) }.getOrDefault(ReaderBg.BLACK),
    )

    fun setTheme(t: ThemeSettings) {
        p.edit()
            .putString("themeMode", t.mode.name)
            .putLong("accent", t.accent)
            .putBoolean("dynamic", t.dynamic)
            .putString("readerBg", t.readerBg.name)
            .apply()
        _theme.value = t
    }

    fun setFolder(uri: String?) {
        p.edit().putString("folder", uri).apply()
        _folder.value = uri
    }

    private val _autoUpdate = MutableStateFlow(p.getBoolean("autoUpdate", true))
    /** İnternete bağlıyken yeni sürümü kendiliğinden indir. */
    val autoUpdate = _autoUpdate.asStateFlow()

    fun setAutoUpdate(on: Boolean) {
        p.edit().putBoolean("autoUpdate", on).apply()
        _autoUpdate.value = on
    }

    /** Uygulamanın en son açıldığı sürüm (güncelleme sonrası "neler yeni" göstermek için). */
    var lastVersion: String?
        get() = p.getString("lastVersion", null)
        set(v) = p.edit().putString("lastVersion", v).apply()

    /** İndirilen güncellemenin notu; kurulduktan sonra internet olmasa da gösterilebilsin. */
    fun savePendingNotes(version: String, notes: String) {
        p.edit().putString("pendingVersion", version).putString("pendingNotes", notes).apply()
    }

    fun pendingNotes(version: String): String? =
        if (p.getString("pendingVersion", null) == version) p.getString("pendingNotes", null) else null

    fun finishSetup() {
        p.edit().putBoolean("setupDone", true).apply()
        _setupDone.value = true
    }
}
