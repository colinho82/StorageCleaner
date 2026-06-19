package com.storagecleaner.util

import android.net.Uri
import com.storagecleaner.data.db.CachedDuplicateDao
import com.storagecleaner.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Serialises DuplicateGroup lists to/from Room so the "Duplicates Found"
 * page survives app restarts without re-scanning.
 */
@Singleton
class DuplicateCache @Inject constructor(
    private val dao: CachedDuplicateDao
) {
    suspend fun save(
        groups: List<DuplicateGroup>,
        scannedFolders: List<String> = emptyList()
    ) = withContext(Dispatchers.IO) {
        dao.clearAll()
        val rows = groups.map { group ->
            CachedDuplicate(
                groupId              = group.groupId,
                filesJson            = serializeFiles(group.files),
                fileType             = group.fileType.name,
                matchType            = group.matchType.name,
                similarityPct        = group.similarityPct,
                confidenceScore      = group.confidenceScore,
                recommendedKeepIndex = group.recommendedKeepIndex,
                keepReasonsJson      = JSONArray(group.keepReasons).toString(),
                totalWastedBytes     = group.totalWastedBytes,
                sourceFolders        = scannedFolders.joinToString(",")
            )
        }
        dao.insertAll(rows)
    }

    suspend fun load(): List<DuplicateGroup> = withContext(Dispatchers.IO) {
        dao.getAll().map { cached ->
            DuplicateGroup(
                groupId              = cached.groupId,
                files                = deserializeFiles(cached.filesJson),
                fileType             = runCatching { FileType.valueOf(cached.fileType) }.getOrDefault(FileType.OTHER),
                matchType            = runCatching { MatchType.valueOf(cached.matchType) }.getOrDefault(MatchType.EXACT_IMAGE),
                similarityPct        = cached.similarityPct,
                confidenceScore      = cached.confidenceScore,
                recommendedKeepIndex = cached.recommendedKeepIndex,
                keepReasons          = deserializeReasons(cached.keepReasonsJson)
            )
        }
    }

    suspend fun clear() = dao.clearAll()
    suspend fun count() = dao.count()
    suspend fun totalWasted() = dao.totalWasted() ?: 0L
    suspend fun lastScanTime() = dao.lastScanTime()
    suspend fun removeGroup(groupId: String) = dao.deleteByGroupId(groupId)

    // ── JSON helpers ───────────────────────────────────────────────────────────
    private fun serializeFiles(files: List<ScannedFile>): String {
        val arr = JSONArray()
        files.forEach { f ->
            arr.put(JSONObject().apply {
                put("id", f.id)
                put("uri", f.uri.toString())
                put("name", f.name)
                put("path", f.path)
                put("size", f.size)
                put("mimeType", f.mimeType)
                put("fileType", f.fileType.name)
                put("dateModified", f.dateModified)
                put("hash", f.hash)
                put("width", f.width)
                put("height", f.height)
            })
        }
        return arr.toString()
    }

    private fun deserializeFiles(json: String): List<ScannedFile> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ScannedFile(
                id = o.getLong("id"),
                uri = Uri.parse(o.getString("uri")),
                name = o.getString("name"),
                path = o.getString("path"),
                size = o.getLong("size"),
                mimeType = o.getString("mimeType"),
                fileType = runCatching { FileType.valueOf(o.getString("fileType")) }.getOrDefault(FileType.OTHER),
                dateModified = o.getLong("dateModified"),
                hash = o.optString("hash", ""),
                width = o.optInt("width", 0),
                height = o.optInt("height", 0)
            )
        }
    } catch (e: Exception) { emptyList() }

    private fun deserializeReasons(json: String): List<String> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { arr.getString(it) }
    } catch (e: Exception) { emptyList() }
}
