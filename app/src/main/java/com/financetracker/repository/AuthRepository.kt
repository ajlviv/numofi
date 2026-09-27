package com.financetracker.repository

import com.financetracker.model.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val userRepository: UserRepository,
    private val sessionRepository: SessionRepository
) {

    /**
     * The signed-in uid, or null when nobody is signed in. Exposed as a flow so
     * screens can scope their queries reactively instead of reading it once.
     */
    val currentUid: Flow<String?> = sessionRepository.signedInUid

    /**
     * Resolves the locally cached profile for the stored uid, or null when nobody
     * is signed in. Intentionally read-only: Google owns the credential, this app
     * only mirrors the account it belongs to.
     */
    suspend fun currentUser(): User? {
        val uid = sessionRepository.signedInUid.first() ?: return null
        return userRepository.getUserById(uid)
    }

    /** Caches the profile and marks it as the active account. */
    suspend fun signIn(user: User): User {
        userRepository.createUser(user)
        sessionRepository.signIn(user.uid)
        return user
    }

    suspend fun signOut() {
        sessionRepository.signOut()
    }
}
