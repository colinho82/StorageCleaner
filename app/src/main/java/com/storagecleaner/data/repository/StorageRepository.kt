package com.storagecleaner.data.repository

import com.storagecleaner.data.db.*
import com.storagecleaner.data.model.*
import com.storagecleaner.util.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StorageRepository @Inject constructor(
    private val scanner: StorageScanner,
    private val actionManager: FileActionManager,
    private val cache: DuplicateCache,
    private val archivedFileDao: ArchivedFileDao,
    private val protectedFileDao: ProtectedFileDao,
    private val ignoreRuleDao: IgnoreRuleDao,
    private val scanSessionDao: ScanSessionDao,
    private val storageStatsHelper: StorageStatsHelper,
    private val notificationHelper: NotificationHelper
) {
    val scanProgress: Flow<ScanProgress> = scanner.scanProgress

    /** One-shot event emitted when a scan finishes. Uses SharedFlow with replay=1
     *  so it is never lost even if the fragment subscribes slightly after emission. */
    val scanCompletedEvent = scanner.scanCompletedEvent

    // ════════════════════════════════════════════════════════════════════════
    //  SCANNING
    // ════════════════════════════════════════════════════════════════════════
    suspend fun startScan(): List<DuplicateGroup> = startScanWithScope(ScanScope())

    suspend fun startScanWithScope(scope: ScanScope): List<DuplicateGroup> {
        applyIgnoreRulesAndProtections()
        val startedAt = System.currentTimeMillis()
        val groups = scanner.scanWithScope(scope)
        cache.save(groups, scope.folders.map { it.path })

        // Use wall-clock duration — never block on scanProgress.first() because
        // StateFlow won't re-emit an already-current value, causing a hang.
        val durationMs = System.currentTimeMillis() - startedAt
        scanSessionDao.insert(
            ScanSession(
                startedAt = startedAt,
                durationMs = durationMs,
                foldersScanned = scope.folders.joinToString(",") { it.path },
                filesScanned = groups.sumOf { it.files.size },
                duplicateGroupsFound = groups.size,
                recoverableBytes = groups.sumOf { it.totalWastedBytes },
                scanType = scope.mode.name
            )
        )

        notificationHelper.notifyScanComplete(formatSize(groups.sumOf { it.totalWastedBytes }), groups.size)
        return groups
    }

    fun resetScan() = scanner.reset()

    /** Pushes current ignore-rules / protected-files into the scanner before scanning */
    private suspend fun applyIgnoreRulesAndProtections() {
        val rules = ignoreRuleDao.getEnabledRules()
        scanner.ignoredFolderPaths = rules.filter { it.ruleType == "FOLDER" }.map { it.value }.toSet()
        scanner.ignoredMimePrefixes = rules.filter { it.ruleType == "MIME_PREFIX" }.map { it.value }.toSet()
        scanner.protectedUris = protectedFileDao.getAllUris().toSet()
    }

    // ════════════════════════════════════════════════════════════════════════
    //  CACHE (Duplicates Found persistence)
    // ════════════════════════════════════════════════════════════════════════
    suspend fun getCachedDuplicates()      = cache.load()
    suspend fun hasCachedResults()         = cache.count() > 0
    suspend fun clearCache()               = cache.clear()
    suspend fun clearCachedDuplicates()    = cache.clear()
    suspend fun saveCachedDuplicates(groups: List<DuplicateGroup>) = cache.save(groups)
    suspend fun removeCachedGroup(groupId: String) = cache.removeGroup(groupId)
    suspend fun lastScanTime() = cache.lastScanTime()
    suspend fun totalWastedCached() = cache.totalWasted()

    // ════════════════════════════════════════════════════════════════════════
    //  ARCHIVE  (move / restore)
    // ════════════════════════════════════════════════════════════════════════
    suspend fun moveToArchive(files: List<ScannedFile>, onProgress: (Int, Int) -> Unit = { _, _ -> }): MoveResult {
        val folderName = actionManager.generateArchiveFolderName()
        val result = actionManager.moveToArchive(files, folderName, onProgress)
        recordArchived(result.succeeded, folderName)
        if (result.succeeded.isNotEmpty()) {
            notificationHelper.notifyArchiveComplete(result.succeeded.size)
            // Track actual reclaimed bytes against the latest scan session
            val reclaimed = result.succeeded.sumOf { it.first.size }
            scanSessionDao.getLatest()?.let { latest ->
                scanSessionDao.update(latest.copy(actualReclaimedBytes = latest.actualReclaimedBytes + reclaimed))
            }
        }
        return result
    }

    private suspend fun recordArchived(moved: List<Pair<ScannedFile, String>>, folderName: String) {
        val entries = moved.map { (file, archivedPath) ->
            ArchivedFile(
                originalUri   = file.uri.toString(),
                name          = file.name,
                originalPath  = file.path,
                archivedPath  = archivedPath,
                size          = file.size,
                fileType      = file.fileType.name,
                archiveFolder = folderName,
                hash          = file.hash
            )
        }
        archivedFileDao.insertAll(entries)
    }

    fun getArchivedFiles(): Flow<List<ArchivedFile>> = archivedFileDao.getAllFlow()
    fun getArchiveFolders(): Flow<List<String>>       = archivedFileDao.getFoldersFlow()
    suspend fun deleteArchivedEntry(f: ArchivedFile)  = archivedFileDao.delete(f)

    /** Restore an archived file back to its original folder (v6-lite §11) */
    suspend fun restoreArchivedFiles(files: List<ArchivedFile>): RestoreResult {
        var success = 0; var failure = 0
        files.forEach { file ->
            val ok = actionManager.restoreFromArchive(file.archivedPath, file.originalPath, file.name)
            if (ok) {
                archivedFileDao.update(file.copy(restored = true))
                success++
            } else failure++
        }
        if (success > 0) notificationHelper.notifyRestoreComplete(success)
        return RestoreResult(success, failure)
    }

    // ════════════════════════════════════════════════════════════════════════
    //  PROTECTED FILES (v6-lite §12)
    // ════════════════════════════════════════════════════════════════════════
    fun getProtectedFiles(): Flow<List<ProtectedFile>> = protectedFileDao.getAllFlow()
    suspend fun protectFile(file: ScannedFile) = protectedFileDao.insert(
        ProtectedFile(uri = file.uri.toString(), name = file.name, path = file.path)
    )
    suspend fun unprotectFile(uri: String) = protectedFileDao.deleteByUri(uri)
    suspend fun isProtected(uri: String) = protectedFileDao.isProtected(uri)

    // ════════════════════════════════════════════════════════════════════════
    //  IGNORE RULES (v6-lite §13)
    // ════════════════════════════════════════════════════════════════════════
    fun getIgnoreRules(): Flow<List<IgnoreRule>> = ignoreRuleDao.getAllFlow()
    suspend fun addIgnoreRule(rule: IgnoreRule) = ignoreRuleDao.insert(rule)
    suspend fun updateIgnoreRule(rule: IgnoreRule) = ignoreRuleDao.update(rule)
    suspend fun deleteIgnoreRule(rule: IgnoreRule) = ignoreRuleDao.delete(rule)

    /** Seed common ignore-rule presets on first run (no-op if rules already exist). */
    suspend fun seedDefaultIgnoreRulesIfEmpty() {
        if (ignoreRuleDao.getAllFlow().first().isEmpty()) {
            DefaultIgnoreRules.presets().forEach { ignoreRuleDao.insert(it) }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  SCAN HISTORY (v6-lite §15)
    // ════════════════════════════════════════════════════════════════════════
    fun getScanHistory(): Flow<List<ScanSession>> = scanSessionDao.getAllFlow()
    suspend fun getLatestScanSession() = scanSessionDao.getLatest()
    suspend fun totalReclaimedAllTime() = scanSessionDao.totalReclaimed() ?: 0L
    suspend fun clearScanHistory() = scanSessionDao.clearAll()

    // ════════════════════════════════════════════════════════════════════════
    //  STORAGE DASHBOARD (v6-lite §3.1)
    // ════════════════════════════════════════════════════════════════════════
    suspend fun getDashboardData(): StorageDashboardData {
        val potential = cache.totalWasted()
        val lastScan = cache.lastScanTime()
        return storageStatsHelper.getDashboardData(potential, lastScan)
    }

    // ════════════════════════════════════════════════════════════════════════
    //  STORAGE ANALYTICS (v6-lite §18)
    // ════════════════════════════════════════════════════════════════════════
    suspend fun getLargestFiles(limit: Int = 100)   = storageStatsHelper.getLargestFiles(limit)
    suspend fun getLargestFolders(limit: Int = 50)  = storageStatsHelper.getLargestFolders(limit)

    // ════════════════════════════════════════════════════════════════════════
    //  SETTINGS — thresholds (held in-memory on the scanner singleton)
    // ════════════════════════════════════════════════════════════════════════
    fun setImageSimilarityThreshold(value: Double) { scanner.imageSimilarityThreshold = value }
    fun setDocumentSimilarityThreshold(value: Double) { scanner.documentSimilarityThreshold = value }
    fun getImageSimilarityThreshold() = scanner.imageSimilarityThreshold
    fun getDocumentSimilarityThreshold() = scanner.documentSimilarityThreshold

    // ════════════════════════════════════════════════════════════════════════
    //  UTILITIES
    // ════════════════════════════════════════════════════════════════════════
    fun generateArchiveFolderName() = actionManager.generateArchiveFolderName()
    fun formatSize(bytes: Long)     = actionManager.formatSize(bytes)
    fun getArchivalRoot()           = actionManager.getArchivalRoot()
}
