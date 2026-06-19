package com.storagecleaner.util

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import com.storagecleaner.data.model.FileType
import com.storagecleaner.data.model.LargestFileEntry
import com.storagecleaner.data.model.LargestFolderEntry
import com.storagecleaner.data.model.StorageBreakdownItem
import com.storagecleaner.data.model.StorageDashboardData
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides the Storage Dashboard metrics (v6-lite §3.1) using StatFs and
 * lightweight MediaStore aggregate queries — no extra permissions beyond
 * what the rest of the app already requires.
 */
@Singleton
class StorageStatsHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    suspend fun getDashboardData(
        potentialRecoveryBytes: Long,
        lastScanAt: Long?
    ): StorageDashboardData = withContext(Dispatchers.IO) {
        val stat = StatFs(Environment.getExternalStorageDirectory().path)
        val total = stat.totalBytes
        val free  = stat.availableBytes
        val used  = total - free

        val breakdown = listOf(
            StorageBreakdownItem("Photos",    "📷", sizeForMime(MediaStore.Images.Media.EXTERNAL_CONTENT_URI), FileType.IMAGE),
            StorageBreakdownItem("Videos",    "🎬", sizeForMime(MediaStore.Video.Media.EXTERNAL_CONTENT_URI), FileType.VIDEO),
            StorageBreakdownItem("Audio",     "🎵", sizeForMime(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI), FileType.AUDIO),
            StorageBreakdownItem("Documents", "📄", documentsSize(), FileType.DOCUMENT),
            StorageBreakdownItem("Downloads", "⬇️", downloadsSize(), null),
        ).let { items ->
            val knownTotal = items.sumOf { it.bytes }
            val other = (used - knownTotal).coerceAtLeast(0L)
            items + StorageBreakdownItem("Other", "📦", other, FileType.OTHER)
        }

        StorageDashboardData(
            totalBytes = total,
            usedBytes = used,
            freeBytes = free,
            potentialRecoveryBytes = potentialRecoveryBytes,
            lastScanAt = lastScanAt,
            breakdown = breakdown
        )
    }

    private fun sizeForMime(uri: android.net.Uri): Long {
        return try {
            var total = 0L
            val projection = arrayOf(MediaStore.MediaColumns.SIZE)
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                while (c.moveToNext()) total += c.getLong(sizeCol)
            }
            total
        } catch (e: Exception) { 0L }
    }

    private fun documentsSize(): Long {
        return try {
            var total = 0L
            val mimeTypes = arrayOf(
                "application/pdf",
                "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.ms-excel",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "text/plain", "text/csv"
            )
            val selection = mimeTypes.joinToString(" OR ") { "${MediaStore.Files.FileColumns.MIME_TYPE} = ?" }
            val projection = arrayOf(MediaStore.Files.FileColumns.SIZE)
            context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                projection, selection, mimeTypes, null
            )?.use { c ->
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                while (c.moveToNext()) total += c.getLong(sizeCol)
            }
            total
        } catch (e: Exception) { 0L }
    }

    private fun downloadsSize(): Long {
        return try {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } catch (e: Exception) { 0L }
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "%.2f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576     -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024         -> "%.0f KB".format(bytes / 1_024.0)
        else                   -> "$bytes B"
    }

    // ════════════════════════════════════════════════════════════════════════
    //  STORAGE ANALYTICS (v6-lite §18)
    // ════════════════════════════════════════════════════════════════════════

    /** Top N largest individual files on the device, via MediaStore (no filesystem walk). */
    suspend fun getLargestFiles(limit: Int = 100): List<LargestFileEntry> = withContext(Dispatchers.IO) {
        val results = mutableListOf<LargestFileEntry>()
        val projection = arrayOf(
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DATE_MODIFIED
        )
        try {
            context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                projection,
                "${MediaStore.Files.FileColumns.SIZE} > 0",
                null,
                "${MediaStore.Files.FileColumns.SIZE} DESC LIMIT $limit"
            )?.use { c ->
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val dataCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val mimeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                while (c.moveToNext()) {
                    val path = c.getString(dataCol) ?: continue
                    results += LargestFileEntry(
                        name = c.getString(nameCol) ?: path.substringAfterLast('/'),
                        path = path,
                        size = c.getLong(sizeCol),
                        fileType = mimeToFileType(c.getString(mimeCol) ?: ""),
                        dateModified = c.getLong(dateCol) * 1000L
                    )
                }
            }
        } catch (e: Exception) { /* ignore — show whatever we gathered */ }
        results
    }

    /** Top N folders by total content size, aggregated from MediaStore entries (no filesystem walk). */
    suspend fun getLargestFolders(limit: Int = 50): List<LargestFolderEntry> = withContext(Dispatchers.IO) {
        val sizes = mutableMapOf<String, Long>()
        val counts = mutableMapOf<String, Int>()
        val projection = arrayOf(MediaStore.Files.FileColumns.DATA, MediaStore.Files.FileColumns.SIZE)
        try {
            context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                projection,
                "${MediaStore.Files.FileColumns.SIZE} > 0",
                null, null
            )?.use { c ->
                val dataCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                while (c.moveToNext()) {
                    val path = c.getString(dataCol) ?: continue
                    val size = c.getLong(sizeCol)
                    val parent = path.substringBeforeLast('/', "")
                    if (parent.isEmpty()) continue
                    sizes[parent] = (sizes[parent] ?: 0L) + size
                    counts[parent] = (counts[parent] ?: 0) + 1
                }
            }
        } catch (e: Exception) { /* ignore — show whatever we gathered */ }

        sizes.entries.sortedByDescending { it.value }.take(limit).map { (path, total) ->
            LargestFolderEntry(
                path = path,
                name = path.substringAfterLast('/'),
                totalBytes = total,
                fileCount = counts[path] ?: 0
            )
        }
    }

    private fun mimeToFileType(mime: String): FileType = when {
        mime.startsWith("image/") -> FileType.IMAGE
        mime.startsWith("video/") -> FileType.VIDEO
        mime.startsWith("audio/") -> FileType.AUDIO
        mime.contains("pdf") || mime.contains("word") || mime.contains("excel") || mime.startsWith("text/") -> FileType.DOCUMENT
        mime.contains("apk") -> FileType.APK
        else -> FileType.OTHER
    }
}
