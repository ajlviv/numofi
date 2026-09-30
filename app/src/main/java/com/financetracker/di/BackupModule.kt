package com.financetracker.di

import com.financetracker.data.backup.BackupRequests
import com.financetracker.data.backup.BackupStore
import com.financetracker.data.backup.BackupUploader
import com.financetracker.data.backup.SafBackupStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BackupModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The interface rather than the class, so the uploader depends on "somewhere to write" and
     * a test can supply a folder that records instead of a document provider.
     */
    @Provides
    fun provideBackupStore(store: SafBackupStore): BackupStore = store

    /**
     * Every write path takes the interface, so this is the one place the real uploader is
     * handed out. Bound to the singleton deliberately: it owns the queue that collapses a
     * burst of writes, and a second instance would mean a second queue and a second upload for
     * the same change.
     */
    @Provides
    @Singleton
    fun provideBackupRequests(uploader: BackupUploader): BackupRequests = uploader
}
