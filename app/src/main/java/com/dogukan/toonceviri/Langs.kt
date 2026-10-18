package com.dogukan.toonceviri

import com.google.mlkit.nl.translate.TranslateLanguage

data class Lang(val code: String, val name: String)

object Langs {
    /** OCR'ın okuyabildiği diller (Latin alfabesi + Korece/Japonca/Çince). */
    val source = listOf(
        Lang(TranslateLanguage.ENGLISH, "İngilizce"),
        Lang(TranslateLanguage.TURKISH, "Türkçe"),
        Lang(TranslateLanguage.KOREAN, "Korece"),
        Lang(TranslateLanguage.JAPANESE, "Japonca"),
        Lang(TranslateLanguage.CHINESE, "Çince"),
        Lang(TranslateLanguage.SPANISH, "İspanyolca"),
        Lang(TranslateLanguage.FRENCH, "Fransızca"),
        Lang(TranslateLanguage.GERMAN, "Almanca"),
        Lang(TranslateLanguage.PORTUGUESE, "Portekizce"),
        Lang(TranslateLanguage.ITALIAN, "İtalyanca"),
        Lang(TranslateLanguage.INDONESIAN, "Endonezce"),
        Lang(TranslateLanguage.VIETNAMESE, "Vietnamca"),
    )

    /** Hedef olarak bunlara ek diller de seçilebilir. */
    val target = source + listOf(
        Lang(TranslateLanguage.RUSSIAN, "Rusça"),
        Lang(TranslateLanguage.ARABIC, "Arapça"),
        Lang(TranslateLanguage.THAI, "Tayca"),
    )

    fun name(code: String) = target.firstOrNull { it.code == code }?.name ?: code
    fun short(code: String) = code.uppercase()
}
