package com.minhphan.launcher

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * The launcher speaks Vietnamese, whatever language the head unit itself is set to: its words, and the way it writes
 * numbers, dates and times ("16,1 V", "3.239 rpm", "Thứ hai"). The English strings stay in the app, unused.
 */
internal val APP_LOCALE: Locale = Locale.forLanguageTag("vi-VN")

/** Makes [APP_LOCALE] the default for formatting; Android puts the system's back on every configuration change. */
internal fun useAppLocale() {
    if (Locale.getDefault() != APP_LOCALE) Locale.setDefault(APP_LOCALE)
}

/** Only the language: applied over whatever else the system says, so day and night, size and the rest still follow it. */
internal fun appLanguageOverride(): Configuration = Configuration().apply { setLocale(APP_LOCALE) }

/** [this] context in [APP_LOCALE], for the application and the services, which have no override of their own. */
internal fun Context.inAppLanguage(): Context {
    useAppLocale()
    return createConfigurationContext(Configuration(resources.configuration).apply { setLocale(APP_LOCALE) })
}
