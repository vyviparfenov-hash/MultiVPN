package com.amneziaclient.simple.di

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

/**
 * Все данные (текст .conf и список выбранных пакетов) хранятся ТОЛЬКО
 * в EncryptedSharedPreferences (AES256_GCM для значений, AES256_SIV для ключей).
 * Никаких открытых файлов на диске.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    private const val PREFS_FILE_NAME = "amnezia_secure_prefs"

    @Provides
    @Singleton
    @Named("securePrefs")
    fun provideEncryptedSharedPreferences(
        @ApplicationContext context: Context
    ): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
}
