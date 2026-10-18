package com.dogukan.toonceviri

import android.content.Context
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await

/** Bir dilin cihazdaki durumu: çeviri modeli ve (gerekiyorsa) yazı tanıma modeli. */
data class ModelStatus(
    val lang: Lang,
    val translate: Boolean,
    val ocr: Boolean,
    val builtIn: Boolean,
) {
    val ready get() = translate && ocr
}

/**
 * Dil modellerini yönetir.
 * - Türkçe ve İngilizce hazır gelir (Türkçe çeviri modeli ilk açılışta bir kez iner).
 * - Diğer dillerin çeviri modeli (~30 MB) ve Korece/Japonca/Çince yazı tanıma modeli kullanıcı isterse iner.
 */
class ModelManager(private val ctx: Context) {

    private val remote = RemoteModelManager.getInstance()
    private val installer get() = ModuleInstall.getClient(ctx)

    val builtIn = setOf(TranslateLanguage.ENGLISH, TranslateLanguage.TURKISH)
    private val needsOcrModule = setOf(TranslateLanguage.KOREAN, TranslateLanguage.JAPANESE, TranslateLanguage.CHINESE)

    private fun recognizer(code: String): TextRecognizer? = when (code) {
        TranslateLanguage.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        TranslateLanguage.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        TranslateLanguage.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        else -> null
    }

    private suspend fun downloadedTranslations(): Set<String> =
        remote.getDownloadedModels(TranslateRemoteModel::class.java).await().map { it.language }.toSet() +
            TranslateLanguage.ENGLISH // İngilizce her zaman cihazda

    private suspend fun ocrReady(code: String): Boolean {
        if (code !in needsOcrModule) return true
        val r = recognizer(code) ?: return true
        return try {
            installer.areModulesAvailable(r).await().areModulesAvailable()
        } catch (e: Exception) {
            false
        } finally {
            r.close()
        }
    }

    suspend fun statuses(): List<ModelStatus> {
        val tr = downloadedTranslations()
        return Langs.target.map { l ->
            ModelStatus(
                lang = l,
                translate = l.code in tr,
                ocr = ocrReady(l.code),
                builtIn = l.code in builtIn,
            )
        }
    }

    /** Kaynak dil için çeviri + yazı tanıma, hedef dil için sadece çeviri modeli gerekir. */
    suspend fun isReady(code: String, asSource: Boolean): Boolean {
        if (code !in downloadedTranslations()) return false
        return !asSource || ocrReady(code)
    }

    suspend fun download(code: String) {
        if (code != TranslateLanguage.ENGLISH) {
            remote.download(TranslateRemoteModel.Builder(code).build(), DownloadConditions.Builder().build()).await()
        }
        val r = recognizer(code) ?: return
        try {
            val resp = installer.installModules(ModuleInstallRequest.newBuilder().addApi(r).build()).await()
            if (!resp.areModulesAlreadyInstalled()) {
                // Kurulum Play Hizmetleri tarafından arka planda sürer; bitene kadar bekle (en fazla ~5 dk)
                repeat(150) {
                    if (installer.areModulesAvailable(r).await().areModulesAvailable()) return
                    delay(2000)
                }
                throw IllegalStateException("Yazı tanıma modeli inemedi. İnternet ve Google Play Hizmetleri'ni kontrol et.")
            }
        } finally {
            r.close()
        }
    }

    suspend fun delete(code: String) {
        if (code in builtIn) return
        remote.deleteDownloadedModel(TranslateRemoteModel.Builder(code).build()).await()
        val r = recognizer(code) ?: return
        try {
            installer.releaseModules(r).await()
        } catch (_: Exception) {
        } finally {
            r.close()
        }
    }
}
