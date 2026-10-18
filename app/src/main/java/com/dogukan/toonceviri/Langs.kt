package com.dogukan.toonceviri

import com.google.mlkit.nl.translate.TranslateLanguage
import java.text.Collator
import java.util.Locale

data class Lang(val code: String, val name: String)

object Langs {
    private val tr = Locale("tr")

    /**
     * Yazı tanımanın okuyabildiği diller: Latin alfabeli diller + Korece/Japonca/Çince.
     * (Rusça, Arapça, Hintçe gibi diller yalnızca hedef dil olabilir.)
     */
    private val ocrCodes = setOf(
        TranslateLanguage.AFRIKAANS, TranslateLanguage.ALBANIAN, TranslateLanguage.CATALAN,
        TranslateLanguage.CROATIAN, TranslateLanguage.CZECH, TranslateLanguage.DANISH,
        TranslateLanguage.DUTCH, TranslateLanguage.ENGLISH, TranslateLanguage.ESPERANTO,
        TranslateLanguage.ESTONIAN, TranslateLanguage.FINNISH, TranslateLanguage.FRENCH,
        TranslateLanguage.GALICIAN, TranslateLanguage.GERMAN, TranslateLanguage.HAITIAN_CREOLE,
        TranslateLanguage.HUNGARIAN, TranslateLanguage.ICELANDIC, TranslateLanguage.INDONESIAN,
        TranslateLanguage.IRISH, TranslateLanguage.ITALIAN, TranslateLanguage.LITHUANIAN,
        TranslateLanguage.LATVIAN, TranslateLanguage.MALAY, TranslateLanguage.MALTESE,
        TranslateLanguage.NORWEGIAN, TranslateLanguage.POLISH, TranslateLanguage.PORTUGUESE,
        TranslateLanguage.ROMANIAN, TranslateLanguage.SLOVAK, TranslateLanguage.SLOVENIAN,
        TranslateLanguage.SPANISH, TranslateLanguage.SWAHILI, TranslateLanguage.SWEDISH,
        TranslateLanguage.TAGALOG, TranslateLanguage.TURKISH, TranslateLanguage.VIETNAMESE,
        TranslateLanguage.WELSH,
        TranslateLanguage.KOREAN, TranslateLanguage.JAPANESE, TranslateLanguage.CHINESE,
    )

    private val fixedNames = mapOf(
        TranslateLanguage.ENGLISH to "İngilizce",
        TranslateLanguage.TURKISH to "Türkçe",
        TranslateLanguage.KOREAN to "Korece",
        TranslateLanguage.JAPANESE to "Japonca",
        TranslateLanguage.CHINESE to "Çince",
    )

    private fun displayName(code: String): String =
        fixedNames[code] ?: Locale(code).getDisplayLanguage(tr)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(tr) else it.toString() }
            .ifBlank { code }

    /** Önce Türkçe ve İngilizce, ardından Korece/Japonca/Çince, sonra alfabetik. */
    private val pinned = listOf(
        TranslateLanguage.TURKISH, TranslateLanguage.ENGLISH,
        TranslateLanguage.KOREAN, TranslateLanguage.JAPANESE, TranslateLanguage.CHINESE,
    )

    /** ML Kit çeviri motorunun desteklediği tüm diller (hedef dil olarak). */
    val target: List<Lang> = run {
        val collator = Collator.getInstance(tr)
        val all = TranslateLanguage.getAllLanguages().map { Lang(it, displayName(it)) }
        val first = pinned.mapNotNull { c -> all.firstOrNull { it.code == c } }
        val rest = all.filter { it.code !in pinned }.sortedWith { a, b -> collator.compare(a.name, b.name) }
        first + rest
    }

    /** Kaynak olarak seçilebilen diller (yazı tanıma desteği olanlar). */
    val source: List<Lang> = target.filter { it.code in ocrCodes }

    fun canBeSource(code: String) = code in ocrCodes
    fun name(code: String) = target.firstOrNull { it.code == code }?.name ?: displayName(code)
    fun short(code: String) = code.uppercase(Locale.ROOT)
}
