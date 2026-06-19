package com.storagecleaner.util

import android.content.Context
import android.database.Cursor
import android.os.Environment
import android.provider.MediaStore
import com.storagecleaner.data.model.FolderType
import com.storagecleaner.data.model.PhoneFolder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FolderBrowser @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // ── Photo albums from MediaStore ──────────────────────────────────────────
    suspend fun getPhotoAlbums(): List<PhoneFolder> = withContext(Dispatchers.IO) {
        val folders = mutableMapOf<String, Int>()

        val projection = arrayOf(
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        )
        val cursor: Cursor? = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, null, null,
            "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} ASC"
        )
        cursor?.use {
            val dataCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            while (it.moveToNext()) {
                val path = it.getString(dataCol) ?: continue
                val dir = File(path).parent ?: continue
                folders[dir] = (folders[dir] ?: 0) + 1
            }
        }

        folders.map { (path, count) ->
            PhoneFolder(
                path = path,
                name = File(path).name.ifBlank { path },
                fileCount = count,
                folderType = classifyFolder(path, isImage = true)
            )
        }.sortedByDescending { it.fileCount }
    }

    // ── Document folders ───────────────────────────────────────────────────────
    suspend fun getDocumentFolders(): List<PhoneFolder> = withContext(Dispatchers.IO) {
        val folders = mutableMapOf<String, Int>()

        val mimeTypes = arrayOf(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "text/plain", "text/csv"
        )
        val selection = mimeTypes.joinToString(" OR ") { "${MediaStore.Files.FileColumns.MIME_TYPE} = ?" }
        val projection = arrayOf(MediaStore.Files.FileColumns.DATA)

        val cursor: Cursor? = context.contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            projection, selection, mimeTypes, null
        )
        cursor?.use {
            val dataCol = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
            while (it.moveToNext()) {
                val path = it.getString(dataCol) ?: continue
                val dir = File(path).parent ?: continue
                folders[dir] = (folders[dir] ?: 0) + 1
            }
        }

        val wellKnown = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            File(Environment.getExternalStorageDirectory(), "StorageCleaner")
        )
        wellKnown.forEach { dir ->
            if (dir.exists() && dir.isDirectory) {
                val count = dir.listFiles()?.size ?: 0
                if (count > 0) folders[dir.absolutePath] = maxOf(folders[dir.absolutePath] ?: 0, count)
            }
        }

        folders.map { (path, count) ->
            PhoneFolder(
                path = path,
                name = File(path).name.ifBlank { path },
                fileCount = count,
                folderType = classifyFolder(path, isImage = false)
            )
        }.sortedByDescending { it.fileCount }
    }

    suspend fun getAllFolders(): Pair<List<PhoneFolder>, List<PhoneFolder>> =
        Pair(getPhotoAlbums(), getDocumentFolders())

    // ── Folder classification (v6-lite §3.2 supported folder types) ──────────
    private fun classifyFolder(path: String, isImage: Boolean): FolderType {
        val lower = path.lowercase()
        return when {
            lower.contains("screenshot") -> FolderType.SCREENSHOTS
            lower.contains("whatsapp") || lower.contains("telegram") || lower.contains("messenger") -> FolderType.MESSAGING
            lower.contains("download") -> FolderType.DOWNLOADS
            isImage -> FolderType.PHOTO_ALBUM
            else -> FolderType.DOCUMENT_FOLDER
        }
    }
}
