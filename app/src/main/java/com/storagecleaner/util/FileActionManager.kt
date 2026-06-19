package com.storagecleaner.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.storagecleaner.data.model.ScannedFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

data class MoveResult(
    val succeeded: List<Pair<ScannedFile, String>>, // file → new absolute path
    val failed: List<ScannedFile>
)

data class RestoreResult(
    val succeeded: Int,
    val failed: Int
)

data class DeleteResult(
    val succeeded: List<ScannedFile>,
    val failed: List<ScannedFile>
)

@Singleton
class FileActionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * Root archival folder.
     * Tries Documents/StorageCleaner/ first (needs MANAGE_EXTERNAL_STORAGE on API 30+).
     * Falls back to app-private external storage (never needs special permissions)
     * so archiving works even without "All Files Access" granted.
     */
    fun getArchivalRoot(): File {
        val publicDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "StorageCleaner"
        )
        if (publicDir.exists() || publicDir.mkdirs()) return publicDir

        // Fallback: app-private external files dir — always writable, no permission needed
        val privateDir = File(context.getExternalFilesDir(null), "StorageCleaner")
        privateDir.mkdirs()
        return privateDir
    }

    /** Fallback restore destination if the original folder no longer exists */
    fun getRestoredFilesRoot(): File =
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "RestoredFiles"
        ).also { it.mkdirs() }

    fun generateArchiveFolderName(): String =
        "Archive_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}"

    // ── Step 1-4: Copy → verify hash → trash original → caller records DB ────
    suspend fun moveToArchive(
        files: List<ScannedFile>,
        folderName: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): MoveResult = withContext(Dispatchers.IO) {
        val destDir = File(getArchivalRoot(), folderName).also { it.mkdirs() }
        val succeeded = mutableListOf<Pair<ScannedFile, String>>()
        val failed    = mutableListOf<ScannedFile>()

        files.forEachIndexed { index, file ->
            onProgress(index + 1, files.size)
            try {
                val destFile = uniqueFile(destDir, file.name)

                // Step 1: copy bytes
                context.contentResolver.openInputStream(file.uri)?.use { input ->
                    FileOutputStream(destFile).use { output -> input.copyTo(output) }
                } ?: throw Exception("Cannot open input stream")

                // Step 2: verify integrity via hash comparison
                val sourceHash = file.hash.ifEmpty { md5OfUri(file.uri) }
                val destHash = md5OfFile(destFile)
                if (sourceHash.isNotEmpty() && destHash != sourceHash) {
                    destFile.delete()
                    throw Exception("Hash mismatch after copy")
                }

                // Step 3: move original to recycle bin / delete
                deleteFromDevice(file)

                succeeded += Pair(file, destFile.absolutePath)
            } catch (e: Exception) {
                failed += file
            }
        }
        MoveResult(succeeded, failed)
    }

    // ── Restore: copy archived file back to its original folder (or fallback) ─
    suspend fun restoreFromArchive(
        archivedPath: String,
        originalPath: String,
        fileName: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val src = File(archivedPath)
            if (!src.exists()) return@withContext false

            val originalDir = File(originalPath).parentFile
            val destDir = if (originalDir != null && (originalDir.exists() || originalDir.mkdirs()))
                originalDir else getRestoredFilesRoot()

            val destFile = uniqueFile(destDir, fileName)
            src.copyTo(destFile, overwrite = false)

            // Make the restored file visible in the gallery / MediaStore
            scanFileIntoMediaStore(destFile)

            true
        } catch (e: Exception) { false }
    }

    private fun scanFileIntoMediaStore(file: File) {
        try {
            android.media.MediaScannerConnection.scanFile(
                context, arrayOf(file.absolutePath), null, null
            )
        } catch (_: Exception) {}
    }

    // ── Delete original from device (MediaStore trash on API 30+) ────────────
    suspend fun deleteFromDevice(file: ScannedFile) = withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val cv = ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 1) }
                context.contentResolver.update(file.uri, cv, null, null)
            } else {
                context.contentResolver.delete(file.uri, null, null)
            }
        } catch (_: Exception) {}
    }

    suspend fun deleteFiles(
        files: List<ScannedFile>,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): DeleteResult = withContext(Dispatchers.IO) {
        val succeeded = mutableListOf<ScannedFile>()
        val failed    = mutableListOf<ScannedFile>()
        files.forEachIndexed { i, file ->
            onProgress(i + 1, files.size)
            try { deleteFromDevice(file); succeeded += file }
            catch (e: Exception) { failed += file }
        }
        DeleteResult(succeeded, failed)
    }

    // ── Listing helpers ────────────────────────────────────────────────────────
    fun listArchivalFolders(): List<File> =
        getArchivalRoot().listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun listArchivalFiles(folderName: String): List<File> =
        File(getArchivalRoot(), folderName).listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()

    // ── Hashing helpers ────────────────────────────────────────────────────────
    private fun md5OfFile(file: File): String = try {
        val md = MessageDigest.getInstance("MD5")
        file.inputStream().use { stream ->
            val buf = ByteArray(8192); var n: Int
            while (stream.read(buf).also { n = it } != -1) md.update(buf, 0, n)
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) { "" }

    private fun md5OfUri(uri: android.net.Uri): String = try {
        val md = MessageDigest.getInstance("MD5")
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val buf = ByteArray(8192); var n: Int
            while (stream.read(buf).also { n = it } != -1) md.update(buf, 0, n)
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) { "" }

    // ── Misc helpers ──────────────────────────────────────────────────────────
    private fun uniqueFile(dir: File, name: String): File {
        var dest = File(dir, name)
        var counter = 1
        val base = name.substringBeforeLast(".")
        val ext  = name.substringAfterLast(".", "")
        while (dest.exists()) {
            dest = File(dir, if (ext.isNotEmpty()) "${base}_$counter.$ext" else "${base}_$counter")
            counter++
        }
        return dest
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "%.2f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576     -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024         -> "%.0f KB".format(bytes / 1_024.0)
        else                   -> "$bytes B"
    }
}
