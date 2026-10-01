package com.financetracker.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.financetracker.data.settings.SettingsRepository
import com.financetracker.data.settings.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * App-wide preferences needed above the screen level: the theme, and whether the app is locked.
 *
 * Both are read here rather than in a screen's own ViewModel because both are needed above the
 * screen level — the theme decides the window the whole tree renders in, and the lock decides
 * whether the tree renders at all.
 */
@HiltViewModel
class AppViewModel @Inject constructor(
    settingsRepository: SettingsRepository
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = settingsRepository.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.SYSTEM)

    /**
     * Whether the app lock is on, or null while the preference has not been read yet.
     *
     * Null rather than a default of false on purpose. DataStore reads asynchronously, so a
     * default would render the dashboard on a cold start for as long as the read takes — briefly,
     * but with balances on screen — and then hide it. The gate treats null as locked, which is
     * the direction to be wrong in: a placeholder for a moment, never a balance.
     */
    val appLockEnabled: StateFlow<Boolean?> = settingsRepository.appLockEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}
