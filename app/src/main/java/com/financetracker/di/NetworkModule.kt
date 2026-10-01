package com.financetracker.di

import com.financetracker.data.bank.BankProviderRegistry
import com.financetracker.data.bank.monobank.MonobankApi
import com.financetracker.data.bank.monobank.MonobankBankProvider
import com.financetracker.data.rates.NbuApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                }
            )
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

    @Provides
    @Singleton
    fun provideMonobankApi(client: OkHttpClient): MonobankApi =
        Retrofit.Builder()
            .baseUrl(MonobankBankProvider.BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(MonobankApi::class.java)

    /**
     * Shares the one OkHttpClient with the bank providers rather than declaring a second.
     *
     * Its timeouts and logging are already right for this, and a separate pool would double the
     * open connections for a host that gets one request a day. Retrofit needs its own instance
     * only because the base URL differs.
     */
    @Provides
    @Singleton
    fun provideNbuApi(client: OkHttpClient): NbuApi =
        Retrofit.Builder()
            .baseUrl(NbuApi.BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(NbuApi::class.java)

    @Provides
    @Singleton
    fun provideMonobankBankProvider(api: MonobankApi): MonobankBankProvider =
        MonobankBankProvider(api)

    /**
     * Adding a bank means adding one @Provides and one entry here; the registry,
     * settings UI, and sync path stay untouched.
     */
    @Provides
    @Singleton
    fun provideBankProviderRegistry(
        monobank: MonobankBankProvider
    ): BankProviderRegistry = BankProviderRegistry(listOf(monobank))
}
