package com.financetracker.util

import android.content.Context
import android.content.res.Configuration
import com.financetracker.data.settings.SettingsRepository
import java.util.Locale

/**
 * Applies the language chosen in Settings to a context before any of its resources are read.
 *
 * Compose resolves every `stringResource` against the activity's resources, so the choice has
 * to land in `attachBaseContext`, where the activity's configuration is still being built — by
 * the time a composable runs, it is too late. The tag itself lives in [SettingsRepository];
 * it is read synchronously there because this hook cannot suspend.
 *
 * Setting [Locale.setDefault] is part of the contract rather than a side effect: the date and
 * money formatters in the data layer ask for the default locale and have no context to ask,
 * so without it a screen would render its own labels in Ukrainian against English month names.
 */
object AppLocale {

    /** Returns [base] reconfigured to the language the user picked in Settings. */
    fun wrap(base: Context): Context {
        val tag = SettingsRepository.storedLanguage(base)
        val device = base.resources.configuration.locales[0]
        val locale =
            if (tag == SettingsRepository.SYSTEM_DEFAULT) device else Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        if (locale == device) return base
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}