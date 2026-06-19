package com.storagecleaner.data.db

import androidx.room.*
import com.storagecleaner.data.model.*
import kotlinx.coroutines.flow.Flow

// ════════════════════════════════════════════════════════════════════════════
//  ARCHIVED FILES
// ════════════════════════════════════════════════════════════════════════════
@Dao
interface ArchivedFileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(file: ArchivedFile): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(files: List<ArchivedFile>)

    @Update
    suspend fun update(file: ArchivedFile)

    @Delete
    suspend fun delete(file: ArchivedFile)

    @Query("DELETE FROM archived_files WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT * FROM archived_files WHERE restored = 0 ORDER BY archivedAt DESC")
    fun getAllFlow(): Flow<List<ArchivedFile>>

    @Query("SELECT * FROM archived_files WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ArchivedFile?

    @Query("SELECT COUNT(*) FROM archived_files WHERE restored = 0")
    suspend fun count(): Int

    @Query("SELECT SUM(size) FROM archived_files WHERE restored = 0")
    suspend fun totalSize(): Long?

    @Query("SELECT DISTINCT archiveFolder FROM archived_files WHERE restored = 0 ORDER BY archiveFolder DESC")
    fun getFoldersFlow(): Flow<List<String>>
}

// ════════════════════════════════════════════════════════════════════════════
//  CACHED DUPLICATES (last scan results)
// ════════════════════════════════════════════════════════════════════════════
@Dao
interface CachedDuplicateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<CachedDuplicate>)

    @Query("SELECT * FROM cached_duplicates ORDER BY totalWastedBytes DESC")
    fun getAllFlow(): Flow<List<CachedDuplicate>>

    @Query("SELECT * FROM cached_duplicates ORDER BY totalWastedBytes DESC")
    suspend fun getAll(): List<CachedDuplicate>

    @Query("DELETE FROM cached_duplicates")
    suspend fun clearAll()

    @Query("DELETE FROM cached_duplicates WHERE groupId = :groupId")
    suspend fun deleteByGroupId(groupId: String)

    @Query("SELECT COUNT(*) FROM cached_duplicates")
    suspend fun count(): Int

    @Query("SELECT SUM(totalWastedBytes) FROM cached_duplicates")
    suspend fun totalWasted(): Long?

    @Query("SELECT scannedAt FROM cached_duplicates ORDER BY scannedAt DESC LIMIT 1")
    suspend fun lastScanTime(): Long?
}

// ════════════════════════════════════════════════════════════════════════════
//  PROTECTED FILES  (v6-lite §12)
// ════════════════════════════════════════════════════════════════════════════
@Dao
interface ProtectedFileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(file: ProtectedFile)

    @Delete
    suspend fun delete(file: ProtectedFile)

    @Query("DELETE FROM protected_files WHERE uri = :uri")
    suspend fun deleteByUri(uri: String)

    @Query("SELECT * FROM protected_files ORDER BY addedAt DESC")
    fun getAllFlow(): Flow<List<ProtectedFile>>

    @Query("SELECT uri FROM protected_files")
    suspend fun getAllUris(): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM protected_files WHERE uri = :uri)")
    suspend fun isProtected(uri: String): Boolean
}

// ════════════════════════════════════════════════════════════════════════════
//  IGNORE RULES  (v6-lite §13)
// ════════════════════════════════════════════════════════════════════════════
@Dao
interface IgnoreRuleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: IgnoreRule): Long

    @Update
    suspend fun update(rule: IgnoreRule)

    @Delete
    suspend fun delete(rule: IgnoreRule)

    @Query("SELECT * FROM ignore_rules ORDER BY id ASC")
    fun getAllFlow(): Flow<List<IgnoreRule>>

    @Query("SELECT * FROM ignore_rules WHERE enabled = 1")
    suspend fun getEnabledRules(): List<IgnoreRule>
}

// ════════════════════════════════════════════════════════════════════════════
//  SCAN HISTORY  (v6-lite §15)
// ════════════════════════════════════════════════════════════════════════════
@Dao
interface ScanSessionDao {
    @Insert
    suspend fun insert(session: ScanSession): Long

    @Update
    suspend fun update(session: ScanSession)

    @Query("SELECT * FROM scan_sessions ORDER BY startedAt DESC")
    fun getAllFlow(): Flow<List<ScanSession>>

    @Query("SELECT * FROM scan_sessions ORDER BY startedAt DESC LIMIT 1")
    suspend fun getLatest(): ScanSession?

    @Query("SELECT SUM(actualReclaimedBytes) FROM scan_sessions")
    suspend fun totalReclaimed(): Long?

    @Query("DELETE FROM scan_sessions")
    suspend fun clearAll()
}

// ════════════════════════════════════════════════════════════════════════════
//  DATABASE
// ════════════════════════════════════════════════════════════════════════════
@Database(
    entities = [
        ArchivedFile::class,
        CachedDuplicate::class,
        ProtectedFile::class,
        IgnoreRule::class,
        ScanSession::class
    ],
    version = 3,
    exportSchema = false
)
abstract class StorageCleanerDatabase : RoomDatabase() {
    abstract fun archivedFileDao(): ArchivedFileDao
    abstract fun cachedDuplicateDao(): CachedDuplicateDao
    abstract fun protectedFileDao(): ProtectedFileDao
    abstract fun ignoreRuleDao(): IgnoreRuleDao
    abstract fun scanSessionDao(): ScanSessionDao

    companion object {
        const val DATABASE_NAME = "storage_cleaner_db"
    }
}
