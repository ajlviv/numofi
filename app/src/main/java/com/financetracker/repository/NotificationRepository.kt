package com.financetracker.repository

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationRepository @Inject constructor() {

    // No notification scheduling yet; kept as the injection point for it.
    fun getPendingNotifications(userId: String): List<String> = emptyList()
}
