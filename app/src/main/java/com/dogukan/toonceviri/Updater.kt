package com.dogukan.toonceviri

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** GitHub'daki bir sürüm. */
data class UpdateInfo(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val size: Long,
)

/**
 * GitHub Releases'tan yeni sürümü denetler, APK'yı indirir ve Android'in kurulum ekranını açar.
 * Android, mağaza dışı uygulamaların kendini sessizce kurmasına izin vermez; kurulum tek dokunuşla onaylanır.
 */
class Updater(private val ctx: Context, private val http: OkHttpClient) {

    private val dir = File(ctx.cacheDir, "updates")

    val currentVersion: String = runCatching {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
    }.getOrNull() ?: "0"

    private fun getJson(url: String): JSONObject {
        val req = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "ToonCeviri")
            .build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            return JSONObject(r.body?.string() ?: throw IOException("Boş yanıt"))
        }
    }

    private fun parse(j: JSONObject): UpdateInfo? {
        val version = j.optString("tag_name").removePrefix("v").trim()
        val assets = j.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) {
                return UpdateInfo(
                    version = version,
                    notes = j.optString("body"),
                    apkUrl = a.getString("browser_download_url"),
                    size = a.optLong("size"),
                )
            }
        }
        return null
    }

    /** En son sürüm bu sürümden yeniyse onu döndürür. */
    fun checkLatest(): UpdateInfo? {
        val info = parse(getJson("$API/releases/latest")) ?: return null
        return if (isNewer(info.version, currentVersion)) info else null
    }

    /** Belirli bir sürümün değişiklik notu (güncellemeden sonra göstermek için). */
    fun notesFor(version: String): String? =
        runCatching { getJson("$API/releases/tags/v$version").optString("body") }.getOrNull()?.ifBlank { null }

    /** APK'yı indirir; daha önce tamamen indirildiyse tekrar indirmez. */
    fun download(info: UpdateInfo, onProgress: (Float) -> Unit): File {
        dir.mkdirs()
        val target = File(dir, "ToonCeviri-v${info.version}.apk")
        if (target.exists() && info.size > 0 && target.length() == info.size) return target
        // Eski sürümlerin indirilmiş dosyalarını temizle
        dir.listFiles()?.forEach { if (it != target) it.delete() }

        val part = File(dir, target.name + ".part")
        val req = Request.Builder().url(info.apkUrl).header("User-Agent", "ToonCeviri").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val body = r.body ?: throw IOException("Boş yanıt")
            val total = body.contentLength().takeIf { it > 0 } ?: info.size
            body.byteStream().use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0) onProgress(read.toFloat() / total)
                    }
                }
            }
        }
        if (info.size > 0 && part.length() != info.size) {
            part.delete()
            throw IOException("İndirme yarım kaldı")
        }
        part.renameTo(target)
        return target
    }

    fun downloaded(version: String): File? =
        File(dir, "ToonCeviri-v$version.apk").takeIf { it.exists() && it.length() > 0 }

    /** Bilinmeyen kaynaklardan kurulum izni (Android 8+ ilk seferde bir kez sorar). */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun installIntent(apk: File): Intent {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    companion object {
        private const val API = "https://api.github.com/repos/01dogukan10/Toonceviri"

        /** "0.13" > "0.12", "1.0" > "0.99", "1.10" > "1.9" */
        fun isNewer(a: String, b: String): Boolean {
            val pa = a.split(".").map { it.toIntOrNull() ?: 0 }
            val pb = b.split(".").map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val x = pa.getOrElse(i) { 0 }
                val y = pb.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
