package com.financetracker.di

import android.content.Context
import com.financetracker.data.AppDatabase
import com.financetracker.data.BankDao
import com.financetracker.data.BondDao
import com.financetracker.data.TransactionDao
import com.financetracker.data.UserDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppDatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        AppDatabase.getInstance(context)

    @Provides
    fun provideTransactionDao(database: AppDatabase): TransactionDao = database.transactionDao()

    @Provides
    fun provideUserDao(database: AppDatabase): UserDao = database.userDao()

    @Provides
    fun provideBankDao(database: AppDatabase): BankDao = database.bankDao()

    @Provides
    fun provideBondDao(database: AppDatabase): BondDao = database.bondDao()
}
