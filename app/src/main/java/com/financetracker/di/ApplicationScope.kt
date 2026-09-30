package com.financetracker.di

import javax.inject.Qualifier

/**
 * A [kotlinx.coroutines.CoroutineScope] that lives as long as the process.
 *
 * For work that must outlive the screen that started it. The uploads are the reason this
 * exists: they are triggered by writes that happen in repositories and services, not by
 * anything the user is looking at, so a `viewModelScope` would cancel them whenever the
 * Settings screen went away.
 *
 * A `SupervisorJob` so one failed child does not take the rest of the application's
 * background work with it.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
