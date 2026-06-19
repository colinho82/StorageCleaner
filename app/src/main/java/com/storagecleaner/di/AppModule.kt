package com.storagecleaner.di

import android.content.Context
import androidx.room.Room
import com.storagecleaner.data.db.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext context: Context): StorageCleanerDatabase =
        Room.databaseBuilder(context, StorageCleanerDatabase::class.java, StorageCleanerDatabase.DATABASE_NAME)
            .fallbackToDestructiveMigration()
            .build()

    @Provides @Singleton
    fun provideArchivedFileDao(db: StorageCleanerDatabase): ArchivedFileDao = db.archivedFileDao()

    @Provides @Singleton
    fun provideCachedDuplicateDao(db: StorageCleanerDatabase): CachedDuplicateDao = db.cachedDuplicateDao()

    @Provides @Singleton
    fun provideProtectedFileDao(db: StorageCleanerDatabase): ProtectedFileDao = db.protectedFileDao()

    @Provides @Singleton
    fun provideIgnoreRuleDao(db: StorageCleanerDatabase): IgnoreRuleDao = db.ignoreRuleDao()

    @Provides @Singleton
    fun provideScanSessionDao(db: StorageCleanerDatabase): ScanSessionDao = db.scanSessionDao()
}
