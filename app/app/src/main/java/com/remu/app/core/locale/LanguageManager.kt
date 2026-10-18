package com.remu.app.core.locale

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

enum class AppLanguage(val tag: String) { ENGLISH("en"), HINDI("hi") }

/**
 * Per-phone language (docs/RULES.md UI rules). AppCompat stores the choice itself (system
 * per-app language on Android 13 and later, AppLocalesMetadataHolderService before that), so it
 * survives restarts and never touches another phone.
 */
open class LanguageManager {
    open fun current(): AppLanguage {
        val tags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        return if (tags.startsWith("hi")) AppLanguage.HINDI else AppLanguage.ENGLISH
    }

    open fun set(language: AppLanguage) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language.tag))
    }
}
