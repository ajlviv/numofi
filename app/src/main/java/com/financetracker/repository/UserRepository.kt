package com.financetracker.repository

import com.financetracker.data.UserDao
import com.financetracker.model.User
import com.financetracker.model.UserEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserRepository @Inject constructor(
    private val dao: UserDao
) {

    suspend fun getUserById(uid: String): User? = dao.getById(uid)?.toDomain()

    suspend fun createUser(user: User): User {
        dao.insert(UserEntity.fromDomain(user))
        return user
    }
}
