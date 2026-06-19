package com.storagecleaner.data.model

import android.net.Uri
import android.os.Parcelable
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

// ════════════════════════════════════════════════════════════════════════════
//  ENUMS
// ════════════════════════════════════════════════════════════════════════════

enum class FileType { IMAGE, VIDEO, AUDIO, DOCUMENT, APK, OTHER }

enum class FolderType { PHOTO_ALBUM, DOCUMENT_FOLDER, DOWNLOADS, MESSAGING, SCREENSHOTS, OTHER }

enum class FileAction { KEEP, MOVE_TO_ARCHIVE }

/**
 * Fine-grained match categories shown to the user (v6-lite §4).
 * Derived from similarity score + metadata comparison — no ML model required.
 */
enum class MatchType {
    // Images
    EXACT_IMAGE,        // byte-identical
    VISUALLY_SIMILAR,   // pHash match, different bytes
    SCREENSHOT_VARIANT, // similar content but very different aspect ratio/resolution
    EDITED_COPY,        // similar pHash but resolution/size differs notably

    // Documents
    EXACT_DOCUMENT,     // byte-identical
    SAME_DOCUMENT,      // identical normalised text content
    SIMILAR_DOCUMENT,   // high text similarity but not identical

    // Videos
    EXACT_VIDEO         // byte-identical (re-encoded/similar video deferred to Phase 2)
}

/** Confidence band shown alongside every group (v6-lite §5) */
enum class ConfidenceLevel { SAFE, REVIEW, MANUAL }

fun confidenceLevelFor(score: Int): ConfidenceLevel = when {
    score >= 95 -> ConfidenceLevel.SAFE
    score >= 85 -> ConfidenceLevel.REVIEW
    else        -> ConfidenceLevel.MANUAL
}

// ════════════════════════════════════════════════════════════════════════════
//  SCANNED FILE
// ════════════════════════════════════════════════════════════════════════════

@Parcelize
data class ScannedFile(
    val id: Long,
    val uri: Uri,
    val name: String,
    val path: String,
    val size: Long,
    val mimeType: String,
    val fileType: FileType,
    val dateModified: Long,
    val hash: String = "",
    val width: Int = 0,           // image/video resolution, 0 if unknown
    val height: Int = 0
) : Parcelable {
    val resolutionPixels: Long get() = width.toLong() * height.toLong()
    val aspectRatio: Float get() = if (height == 0) 0f else width.toFloat() / height.toFloat()
}

// ════════════════════════════════════════════════════════════════════════════
//  DUPLICATE GROUP  (with recommendation + confidence — v6-lite §5 & §6)
// ════════════════════════════════════════════════════════════════════════════

@Parcelize
data class DuplicateGroup(
    val groupId: String,
    val files: List<ScannedFile>,
    val fileType: FileType = files.firstOrNull()?.fileType ?: FileType.OTHER,
    val matchType: MatchType = MatchType.EXACT_IMAGE,
    val similarityPct: Int = 100,
    val confidenceScore: Int = 100,
    /** Index into [files] of the file the engine recommends keeping */
    val recommendedKeepIndex: Int = 0,
    /** Short human-readable reasons for the recommendation, e.g. "Highest resolution" */
    val keepReasons: List<String> = emptyList()
) : Parcelable {
    val totalWastedBytes: Long get() = files.filterIndexed { i, _ -> i != recommendedKeepIndex }
        .sumOf { it.size }
    val representativeFile: ScannedFile get() = files.getOrElse(recommendedKeepIndex) { files.first() }
    val duplicateCount: Int get() = files.size - 1
    val confidenceLevel: ConfidenceLevel get() = confidenceLevelFor(confidenceScore)

    /** Display label for the match type badge */
    val matchLabel: String get() = when (matchType) {
        MatchType.EXACT_IMAGE        -> "EXACT"
        MatchType.VISUALLY_SIMILAR   -> "SIMILAR"
        MatchType.SCREENSHOT_VARIANT -> "SCREENSHOT"
        MatchType.EDITED_COPY        -> "EDITED COPY"
        MatchType.EXACT_DOCUMENT     -> "EXACT"
        MatchType.SAME_DOCUMENT      -> "SAME TEXT"
        MatchType.SIMILAR_DOCUMENT   -> "SIMILAR TEXT"
        MatchType.EXACT_VIDEO        -> "EXACT"
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  ROOM ENTITIES
// ════════════════════════════════════════════════════════════════════════════

/** Cached scan result so "Duplicates Found" survives app restarts (v5/§3.3) */
@Entity(tableName = "cached_duplicates")
data class CachedDuplicate(
    @PrimaryKey val groupId: String,
    val filesJson: String,
    val fileType: String,
    val matchType: String,
    val similarityPct: Int,
    val confidenceScore: Int,
    val recommendedKeepIndex: Int,
    val keepReasonsJson: String,
    val totalWastedBytes: Long,
    val scannedAt: Long = System.currentTimeMillis(),
    val sourceFolders: String = ""
)

/** Archive history record (v5 §3.5 / v6-lite §10-11) */
@Entity(tableName = "archived_files")
data class ArchivedFile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val originalUri: String,
    val name: String,
    val originalPath: String,
    val archivedPath: String,
    val size: Long,
    val fileType: String,
    val matchType: String = MatchType.EXACT_IMAGE.name,
    val archivedAt: Long = System.currentTimeMillis(),
    val archiveFolder: String,
    val hash: String,
    val restored: Boolean = false
)

/** Files the user marked "Never suggest again" (v6-lite §12) */
@Entity(tableName = "protected_files")
data class ProtectedFile(
    @PrimaryKey val uri: String,
    val name: String,
    val path: String,
    val addedAt: Long = System.currentTimeMillis()
)

/** Folder/type-level exclusion rules (v6-lite §13) */
@Entity(tableName = "ignore_rules")
data class IgnoreRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ruleType: String,      // "FOLDER" or "MIME_PREFIX"
    val value: String,         // folder path, or mime prefix e.g. "image/"
    val label: String,         // display label e.g. "WhatsApp Images"
    val enabled: Boolean = true
)

/** Common presets offered on first run of the Ignore Rules screen (v6-lite §13) */
object DefaultIgnoreRules {
    private val sdcard = android.os.Environment.getExternalStorageDirectory().path
    fun presets(): List<IgnoreRule> = listOf(
        IgnoreRule(ruleType = "FOLDER", value = "$sdcard/WhatsApp/Media/WhatsApp Images", label = "WhatsApp Images", enabled = false),
        IgnoreRule(ruleType = "FOLDER", value = "$sdcard/WhatsApp/Media/WhatsApp Video", label = "WhatsApp Videos", enabled = false),
        IgnoreRule(ruleType = "FOLDER", value = "$sdcard/Android/media/org.telegram.messenger/Telegram", label = "Telegram Media", enabled = false),
        IgnoreRule(ruleType = "FOLDER", value = "$sdcard/Pictures/Screenshots", label = "Screenshots", enabled = false),
        IgnoreRule(ruleType = "FOLDER", value = "$sdcard/Download", label = "Downloads", enabled = false)
    )
}

/** One row per completed scan (v6-lite §15) */
@Entity(tableName = "scan_sessions")
data class ScanSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val durationMs: Long,
    val foldersScanned: String,   // comma separated
    val filesScanned: Int,
    val duplicateGroupsFound: Int,
    val recoverableBytes: Long,
    val actualReclaimedBytes: Long = 0L,
    val scanType: String = "FULL" // FULL | QUICK | INCREMENTAL
)

// ════════════════════════════════════════════════════════════════════════════
//  FOLDER BROWSING / SCAN SCOPE
// ════════════════════════════════════════════════════════════════════════════

@Parcelize
data class PhoneFolder(
    val path: String,
    val name: String,
    val fileCount: Int,
    val folderType: FolderType,
    var isSelected: Boolean = false
) : Parcelable

enum class ScanMode { QUICK, FULL, INCREMENTAL }

data class ScanScope(
    val folders: List<PhoneFolder> = emptyList(), // empty = full device scan
    val includeImages: Boolean = true,
    val includeDocuments: Boolean = true,
    val includeVideos: Boolean = false,
    val mode: ScanMode = ScanMode.FULL
)

// ════════════════════════════════════════════════════════════════════════════
//  SCAN PROGRESS / STATS
// ════════════════════════════════════════════════════════════════════════════

sealed class ScanProgress {
    object Idle : ScanProgress()
    data class Scanning(
        val current: Int,
        val total: Int,
        val currentFileName: String,
        val phase: String = ""
    ) : ScanProgress()
    data class Completed(val stats: ScanStats, val groups: List<DuplicateGroup>) : ScanProgress()
    data class Error(val message: String) : ScanProgress()
}

data class ScanStats(
    val totalFilesScanned: Int = 0,
    val duplicateGroupsFound: Int = 0,
    val duplicateFilesCount: Int = 0,
    val totalWastedBytes: Long = 0L,
    val imageGroups: Int = 0,
    val videoGroups: Int = 0,
    val documentGroups: Int = 0,
    val scanDurationMs: Long = 0L,
    val scannedFolders: List<String> = emptyList()
)

// ════════════════════════════════════════════════════════════════════════════
//  STORAGE DASHBOARD (v6-lite §3.1)
// ════════════════════════════════════════════════════════════════════════════

data class StorageBreakdownItem(
    val label: String,
    val emoji: String,
    val bytes: Long,
    val fileType: FileType?
)

data class StorageDashboardData(
    val totalBytes: Long = 0L,
    val usedBytes: Long = 0L,
    val freeBytes: Long = 0L,
    val potentialRecoveryBytes: Long = 0L,
    val lastScanAt: Long? = null,
    val breakdown: List<StorageBreakdownItem> = emptyList()
)

// ════════════════════════════════════════════════════════════════════════════
//  STORAGE ANALYTICS (v6-lite §18)
// ════════════════════════════════════════════════════════════════════════════

data class LargestFileEntry(
    val name: String,
    val path: String,
    val size: Long,
    val fileType: FileType,
    val dateModified: Long
)

data class LargestFolderEntry(
    val path: String,
    val name: String,
    val totalBytes: Long,
    val fileCount: Int
)

// ════════════════════════════════════════════════════════════════════════════
//  SELECTION (used by Duplicates Found + Smart Select)
// ════════════════════════════════════════════════════════════════════════════

data class FileSelection(val file: ScannedFile, var action: FileAction = FileAction.KEEP)
