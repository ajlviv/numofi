package com.financetracker.repository

import com.financetracker.data.UserDao
import com.financetracker.model.User
import com.financetracker.model.UserEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserRepository @Inject constructor(
    private val dao: UserDao
) {

    fun getAllUsers(): Flow<List<User>> = dao.getAll().map { users -> users.map { it.toDomain() } }

    suspend fun getUserById(uid: String): User? = dao.getById(uid)?.toDomain()

    suspend fun createUser(user: User): User {
        dao.insert(UserEntity.fromDomain(user))
        return user
    }

    suspend fun updateUser(user: User): User {
        dao.update(UserEntity.fromDomain(user))
        return user
    }

    suspend fun deleteUser(uid: String): Int = dao.delete(uid)
}
