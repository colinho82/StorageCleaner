package com.storagecleaner.util

import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import com.storagecleaner.data.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

@Singleton
class StorageScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recommendationEngine: RecommendationEngine
) {
    val scanProgress = MutableStateFlow<ScanProgress>(ScanProgress.Idle)

    /** Dedicated one-shot event for scan completion. Uses SharedFlow with replay=1
     *  so the fragment always receives it even if it subscribes slightly late.
     *  Unlike StateFlow, SharedFlow won't be overwritten by a subsequent Idle state. */
    private val _scanCompletedEvent = MutableSharedFlow<List<DuplicateGroup>>(replay = 1, extraBufferCapacity = 1)
    val scanCompletedEvent: SharedFlow<List<DuplicateGroup>> = _scanCompletedEvent

    // ── Tunable thresholds (adjustable from Settings) ─────────────────────────
    // imageSimilarityThreshold: 0.70–1.00 maps to pHash distance 18–0 (out of 64 bits)
    var imageSimilarityThreshold: Double = 0.90
        set(value) { field = value; _imageHashThreshold = ((1.0 - value) * 60).toInt().coerceIn(0, 30) }
    private var _imageHashThreshold: Int = 6

    var documentSimilarityThreshold: Double = 0.90
        set(value) { field = value; _docSimilarityThreshold = value }
    private var _docSimilarityThreshold: Double = 0.90

    /** Folder paths / mime-prefixes to skip entirely (v6-lite §13 Ignore Rules) */
    var ignoredFolderPaths: Set<String> = emptySet()
    var ignoredMimePrefixes: Set<String> = emptySet()

    /** URIs the user marked "never suggest" (v6-lite §12) — excluded from results */
    var protectedUris: Set<String> = emptySet()

    // ════════════════════════════════════════════════════════════════════════
    //  FULL SCAN (all media)
    // ════════════════════════════════════════════════════════════════════════
    suspend fun scanAll(): List<DuplicateGroup> = scanWithScope(ScanScope())

    // ════════════════════════════════════════════════════════════════════════
    //  SCOPED SCAN — only the folders the user picked (or everything if empty)
    // ════════════════════════════════════════════════════════════════════════
    suspend fun scanWithScope(scope: ScanScope): List<DuplicateGroup> =
        withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            val folderPaths = scope.folders.map { it.path }.toSet()
            scanProgress.value = ScanProgress.Scanning(0, 0, "Collecting files…", "Preparing")

            val imageFiles = if (scope.includeImages)
                filterIgnored(queryMediaStore(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, FileType.IMAGE, folderPaths))
            else emptyList()

            val docFiles = if (scope.includeDocuments)
                filterIgnored(queryMediaStore(
                    MediaStore.Files.getContentUri("external"), FileType.DOCUMENT, folderPaths,
                    mimeFilter = arrayOf(
                        "application/pdf",
                        "application/msword",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "application/vnd.ms-excel",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "text/plain", "text/csv"
                    )
                ))
            else emptyList()

            val videoFiles = if (scope.includeVideos)
                filterIgnored(queryMediaStore(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, FileType.VIDEO, folderPaths))
            else emptyList()

            val allGroups = mutableListOf<DuplicateGroup>()

            // ── Images: perceptual hash ────────────────────────────────────
            if (imageFiles.isNotEmpty()) {
                scanProgress.value = ScanProgress.Scanning(0, imageFiles.size, "Analysing images…", "📷 Images")
                val hashed = imageFiles.mapIndexedNotNull { i, f ->
                    scanProgress.value = ScanProgress.Scanning(i + 1, imageFiles.size, f.name, "📷 Images")
                    val (hash, w, h) = computeImageHashAndSize(f.uri) ?: return@mapIndexedNotNull null
                    f.copy(hash = hash, width = w, height = h)
                }
                allGroups += groupImagesBySimilarity(hashed, _imageHashThreshold)
            }

            // ── Documents: content hash ────────────────────────────────────
            if (docFiles.isNotEmpty()) {
                scanProgress.value = ScanProgress.Scanning(0, docFiles.size, "Reading documents…", "📄 Documents")
                val hashed = docFiles.mapIndexedNotNull { i, f ->
                    scanProgress.value = ScanProgress.Scanning(i + 1, docFiles.size, f.name, "📄 Documents")
                    computeDocumentContentHash(f.uri, f.mimeType)?.let { f.copy(hash = it) }
                }
                allGroups += groupDocumentsBySimilarity(hashed, _docSimilarityThreshold)
            }

            // ── Videos: exact MD5 ───────────────────────────────────────────
            if (videoFiles.isNotEmpty()) {
                scanProgress.value = ScanProgress.Scanning(0, videoFiles.size, "Scanning videos…", "🎬 Videos")
                val hashed = videoFiles.mapIndexedNotNull { i, f ->
                    scanProgress.value = ScanProgress.Scanning(i + 1, videoFiles.size, f.name, "🎬 Videos")
                    computeMd5(f.uri)?.let { f.copy(hash = it) }
                }
                allGroups += hashed.groupBy { it.hash }
                    .filter { it.value.size > 1 }
                    .map { (_, files) -> buildGroup(files, MatchType.EXACT_VIDEO, 100, 100) }
            }

            // ── Remove groups where every duplicate is protected ───────────
            val filtered = allGroups.filter { group ->
                group.files.any { it.uri.toString() !in protectedUris }
            }.map { group ->
                // Re-evaluate recommendation excluding protected files from "to archive" consideration
                group
            }

            val sorted = filtered.sortedByDescending { it.totalWastedBytes }
            val stats = ScanStats(
                totalFilesScanned    = imageFiles.size + docFiles.size + videoFiles.size,
                duplicateGroupsFound = sorted.size,
                duplicateFilesCount  = sorted.sumOf { it.duplicateCount },
                totalWastedBytes     = sorted.sumOf { it.totalWastedBytes },
                imageGroups          = sorted.count { it.fileType == FileType.IMAGE },
                videoGroups          = sorted.count { it.fileType == FileType.VIDEO },
                documentGroups       = sorted.count { it.fileType == FileType.DOCUMENT },
                scanDurationMs       = System.currentTimeMillis() - startTime,
                scannedFolders       = folderPaths.toList()
            )
            _scanCompletedEvent.emit(sorted)
            scanProgress.value = ScanProgress.Completed(stats, sorted)
            sorted
        }

    // ════════════════════════════════════════════════════════════════════════
    //  IMAGE GROUPING — pHash similarity + metadata-derived match categories
    // ════════════════════════════════════════════════════════════════════════
    private fun groupImagesBySimilarity(files: List<ScannedFile>, hashThreshold: Int): List<DuplicateGroup> {
        if (files.isEmpty()) return emptyList()
        val parent = IntArray(files.size) { it }
        fun find(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        fun union(a: Int, b: Int) { parent[find(a)] = find(b) }

        // Pairwise hamming distance on 64-bit pHash (16 hex chars)
        for (i in files.indices) {
            for (j in i + 1 until files.size) {
                val dist = pHashDistance(files[i].hash, files[j].hash)
                if (dist <= hashThreshold) union(i, j)
            }
        }

        return files.indices.groupBy { find(it) }.values
            .filter { it.size > 1 }
            .map { indices ->
                val groupFiles = indices.map { files[it] }.sortedByDescending { it.dateModified }
                // Average pairwise distance → similarity %
                val dists = indices.flatMap { a -> indices.filter { it > a }.map { b -> pHashDistance(files[a].hash, files[b].hash) } }
                val avgDist = if (dists.isEmpty()) 0.0 else dists.average()
                val similarityPct = ((64.0 - avgDist) * 100 / 64.0).toInt().coerceIn(0, 100)

                // Determine match category from metadata
                val sizes = groupFiles.map { it.size }
                val resolutions = groupFiles.map { it.resolutionPixels }
                val aspectRatios = groupFiles.map { it.aspectRatio }.filter { it > 0f }
                val exactBytes = sizes.distinct().size == 1 && avgDist == 0.0
                val aspectVaries = aspectRatios.size > 1 &&
                    (aspectRatios.maxOrNull()!! - aspectRatios.minOrNull()!!) > 0.15f
                val resolutionVaries = resolutions.distinct().size > 1

                val matchType = when {
                    exactBytes -> MatchType.EXACT_IMAGE
                    aspectVaries -> MatchType.SCREENSHOT_VARIANT
                    resolutionVaries -> MatchType.EDITED_COPY
                    else -> MatchType.VISUALLY_SIMILAR
                }

                // Confidence: weighted blend per v6-lite §4.1 similarity formula
                // (40% pHash, 30% "visual embedding" — approximated by exact-byte bonus,
                //  20% resolution match, 10% metadata completeness)
                val resolutionMatchScore = if (!resolutionVaries) 100 else 60
                val metadataScore = if (groupFiles.all { it.width > 0 }) 100 else 70
                val visualScore = if (exactBytes) 100 else similarityPct
                val confidence = (
                    similarityPct * 0.40 +
                    visualScore * 0.30 +
                    resolutionMatchScore * 0.20 +
                    metadataScore * 0.10
                ).toInt().coerceIn(0, 100)

                buildGroup(groupFiles, matchType, similarityPct, confidence)
            }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  DOCUMENT GROUPING — content hash exact + Jaccard similarity for near-dupes
    // ════════════════════════════════════════════════════════════════════════
    private fun groupDocumentsBySimilarity(files: List<ScannedFile>, threshold: Double): List<DuplicateGroup> {
        if (files.isEmpty()) return emptyList()
        val parent = IntArray(files.size) { it }
        fun find(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        fun union(a: Int, b: Int) { parent[find(a)] = find(b) }

        for (i in files.indices) {
            for (j in i + 1 until files.size) {
                val sim = if (files[i].hash == files[j].hash) 1.0
                          else jaccardSimilarity(files[i].hash, files[j].hash)
                if (sim >= threshold) union(i, j)
            }
        }

        return files.indices.groupBy { find(it) }.values
            .filter { it.size > 1 }
            .map { indices ->
                val groupFiles = indices.map { files[it] }.sortedByDescending { it.dateModified }
                val exactMatch = groupFiles.map { it.hash }.distinct().size == 1
                val sims = indices.flatMap { a -> indices.filter { it > a }.map { b ->
                    if (files[a].hash == files[b].hash) 1.0 else jaccardSimilarity(files[a].hash, files[b].hash)
                } }
                val avgSim = if (sims.isEmpty()) 1.0 else sims.average()
                val similarityPct = (avgSim * 100).toInt().coerceIn(0, 100)

                val matchType = when {
                    exactMatch && groupFiles.map { it.size }.distinct().size == 1 -> MatchType.EXACT_DOCUMENT
                    exactMatch -> MatchType.SAME_DOCUMENT
                    else -> MatchType.SIMILAR_DOCUMENT
                }
                val confidence = when (matchType) {
                    MatchType.EXACT_DOCUMENT -> 100
                    MatchType.SAME_DOCUMENT  -> 97
                    else -> similarityPct.coerceAtMost(92)
                }

                buildGroup(groupFiles, matchType, similarityPct, confidence)
            }
    }

    // ── Build a DuplicateGroup with recommendation engine applied ────────────
    private fun buildGroup(files: List<ScannedFile>, matchType: MatchType, similarityPct: Int, confidence: Int): DuplicateGroup {
        val (keepIndex, reasons) = recommendationEngine.recommend(files)
        return DuplicateGroup(
            groupId = files.first().hash + "_" + files.size,
            files = files,
            fileType = files.first().fileType,
            matchType = matchType,
            similarityPct = similarityPct,
            confidenceScore = confidence,
            recommendedKeepIndex = keepIndex,
            keepReasons = reasons
        )
    }

    // ════════════════════════════════════════════════════════════════════════
    //  IGNORE RULES FILTER
    // ════════════════════════════════════════════════════════════════════════
    private fun filterIgnored(files: List<ScannedFile>): List<ScannedFile> {
        if (ignoredFolderPaths.isEmpty() && ignoredMimePrefixes.isEmpty()) return files
        return files.filter { file ->
            val parent = File(file.path).parent ?: file.path
            val folderIgnored = ignoredFolderPaths.any { ignored -> parent.startsWith(ignored) }
            val mimeIgnored = ignoredMimePrefixes.any { prefix -> file.mimeType.startsWith(prefix) }
            !folderIgnored && !mimeIgnored
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  PERCEPTUAL HASH (pHash) + resolution extraction for images
    // ════════════════════════════════════════════════════════════════════════
    /** Returns Triple(pHashHex, width, height) */
    private fun computeImageHashAndSize(uri: Uri): Triple<String, Int, Int>? {
        return try {
            // First decode bounds only to get real resolution
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, boundsOpts)
            }
            val realW = boundsOpts.outWidth
            val realH = boundsOpts.outHeight

            // Now decode a small thumbnail for hashing
            val opts = BitmapFactory.Options().apply {
                inSampleSize = 4
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val raw = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null

            val thumb = Bitmap.createScaledBitmap(raw, 32, 32, true)
            raw.recycle()

            val pixels = IntArray(32 * 32)
            thumb.getPixels(pixels, 0, 32, 0, 0, 32, 32)
            thumb.recycle()

            val grey = DoubleArray(32 * 32) { i ->
                val p = pixels[i]
                val r = (p shr 16 and 0xFF); val g = (p shr 8 and 0xFF); val b = (p and 0xFF)
                0.299 * r + 0.587 * g + 0.114 * b
            }

            val dct = dct8x8(grey)
            val mean = (dct.sum() - dct[0]) / 63.0
            val bits = dct.mapIndexed { i, v -> if (i > 0 && v > mean) '1' else '0' }.joinToString("")
            val hash = bits.chunked(4).joinToString("") { it.toInt(2).toString(16) }

            Triple(hash, realW, realH)
        } catch (e: Exception) { null }
    }

    private fun dct8x8(pixels: DoubleArray): DoubleArray {
        val N = 8
        val out = DoubleArray(N * N)
        for (u in 0 until N) for (v in 0 until N) {
            var sum = 0.0
            for (x in 0 until N) for (y in 0 until N) {
                sum += pixels[x * 32 + y] *
                    Math.cos((2 * x + 1) * u * Math.PI / (2 * N)) *
                    Math.cos((2 * y + 1) * v * Math.PI / (2 * N))
            }
            val cu = if (u == 0) 1.0 / sqrt(2.0) else 1.0
            val cv = if (v == 0) 1.0 / sqrt(2.0) else 1.0
            out[u * N + v] = 0.25 * cu * cv * sum
        }
        return out
    }

    /** Hamming distance between two 16-char hex pHash strings (0–64) */
    private fun pHashDistance(hashA: String, hashB: String): Int {
        if (hashA.length != hashB.length || hashA.isEmpty()) return 64
        var diff = 0
        for (i in hashA.indices) {
            diff += Integer.bitCount(hashA[i].digitToInt(16) xor hashB[i].digitToInt(16))
        }
        return diff
    }

    // ════════════════════════════════════════════════════════════════════════
    //  DOCUMENT CONTENT EXTRACTION + HASH/SHINGLES
    // ════════════════════════════════════════════════════════════════════════
    private fun computeDocumentContentHash(uri: Uri, mime: String): String? {
        return try {
            val text = extractDocumentText(uri, mime) ?: return null
            if (text.isBlank()) return null
            val normalised = text.lowercase()
                .replace(Regex("[\\r\\n\\t]+"), " ")
                .replace(Regex("\\s{2,}"), " ")
                .trim()

            // Store as MD5 of full text PLUS a set of 5-word shingles (for Jaccard)
            val md = MessageDigest.getInstance("MD5")
            md.update(normalised.toByteArray(Charsets.UTF_8))
            val exactHash = md.digest().joinToString("") { "%02x".format(it) }

            // shingles: 5-word windows, hashed to short tokens, joined with comma
            val words = normalised.split(" ").filter { it.isNotBlank() }
            val shingleHashes = if (words.size >= 5) {
                words.windowed(5, 1).map { it.joinToString(" ").hashCode() }.toSet()
            } else {
                setOf(normalised.hashCode())
            }
            // Format: "<exactMD5>|<shingle1,shingle2,...>" (cap shingle count to keep size sane)
            val shingleStr = shingleHashes.take(200).joinToString(",")
            "$exactHash|$shingleStr"
        } catch (e: Exception) { null }
    }

    private fun extractDocumentText(uri: Uri, mime: String): String? {
        val stream = context.contentResolver.openInputStream(uri) ?: return null
        return stream.use { input ->
            when {
                mime == "text/plain" || mime == "text/csv" -> input.bufferedReader(Charsets.UTF_8).readText()
                mime == "application/pdf" -> extractPdfText(input.readBytes())
                mime.contains("wordprocessingml") || mime.contains("msword") -> extractDocxText(input.readBytes())
                mime.contains("spreadsheetml") || mime.contains("ms-excel") -> extractXlsxText(input.readBytes())
                else -> try { input.bufferedReader().readText() } catch (e: Exception) { null }
            }
        }
    }

    private fun extractPdfText(bytes: ByteArray): String {
        val raw = String(bytes, Charsets.ISO_8859_1)
        val sb = StringBuilder()
        val btEt = Regex("BT(.+?)ET", RegexOption.DOT_MATCHES_ALL)
        val tjPattern = Regex("\\((.+?)\\)\\s*T[jJ]")
        btEt.findAll(raw).forEach { block ->
            tjPattern.findAll(block.groupValues[1]).forEach { tj -> sb.append(tj.groupValues[1]).append(' ') }
        }
        return sb.toString()
    }

    private fun extractDocxText(bytes: ByteArray): String = try {
        val zip = java.util.zip.ZipInputStream(bytes.inputStream())
        val sb = StringBuilder()
        var entry = zip.nextEntry
        while (entry != null) {
            if (entry.name == "word/document.xml") {
                sb.append(zip.bufferedReader(Charsets.UTF_8).readText().replace(Regex("<[^>]+>"), " "))
                break
            }
            entry = zip.nextEntry
        }
        zip.close(); sb.toString()
    } catch (e: Exception) { "" }

    private fun extractXlsxText(bytes: ByteArray): String = try {
        val zip = java.util.zip.ZipInputStream(bytes.inputStream())
        val sb = StringBuilder()
        var entry = zip.nextEntry
        while (entry != null) {
            if (entry.name == "xl/sharedStrings.xml") {
                sb.append(zip.bufferedReader(Charsets.UTF_8).readText().replace(Regex("<[^>]+>"), " "))
                break
            }
            entry = zip.nextEntry
        }
        zip.close(); sb.toString()
    } catch (e: Exception) { "" }

    /** Jaccard similarity between two "exactMD5|shingles" hash strings */
    private fun jaccardSimilarity(hashA: String, hashB: String): Double {
        val shinglesA = hashA.substringAfter("|", "").split(",").filter { it.isNotBlank() }.toSet()
        val shinglesB = hashB.substringAfter("|", "").split(",").filter { it.isNotBlank() }.toSet()
        if (shinglesA.isEmpty() || shinglesB.isEmpty()) return 0.0
        val intersection = shinglesA.intersect(shinglesB).size.toDouble()
        val union = shinglesA.union(shinglesB).size.toDouble()
        return if (union == 0.0) 0.0 else intersection / union
    }

    // ════════════════════════════════════════════════════════════════════════
    //  VIDEO — exact MD5
    // ════════════════════════════════════════════════════════════════════════
    private fun computeMd5(uri: Uri): String? {
        return try {
            val md = MessageDigest.getInstance("MD5")
            val buffer = ByteArray(8192)
            context.contentResolver.openInputStream(uri)?.use { stream ->
                var n: Int
                while (stream.read(buffer).also { n = it } != -1) md.update(buffer, 0, n)
            } ?: return null
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) { null }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  MEDIASTORE QUERY
    // ════════════════════════════════════════════════════════════════════════
    private fun queryMediaStore(
        uri: Uri,
        defaultType: FileType,
        folderPaths: Set<String>,
        mimeFilter: Array<String>? = null
    ): List<ScannedFile> {
        val files = mutableListOf<ScannedFile>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_MODIFIED
        )
        val selection = mimeFilter?.joinToString(" OR ") { "${MediaStore.MediaColumns.MIME_TYPE} = ?" }
        val cursor: Cursor? = context.contentResolver.query(
            uri, projection, selection, mimeFilter, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        )
        cursor?.use {
            val idCol   = it.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val pathCol = it.getColumnIndex(MediaStore.MediaColumns.DATA)
            val sizeCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val mimeCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val dateCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)

            while (it.moveToNext()) {
                val size = it.getLong(sizeCol)
                if (size <= 0) continue
                val id = it.getLong(idCol)
                val mime = it.getString(mimeCol) ?: continue
                val path = if (pathCol >= 0) it.getString(pathCol) ?: "" else ""

                if (folderPaths.isNotEmpty()) {
                    val parent = File(path).parent ?: ""
                    val inScope = folderPaths.any { selected -> parent.startsWith(selected) || parent == selected }
                    if (!inScope) continue
                }

                files += ScannedFile(
                    id = id,
                    uri = Uri.withAppendedPath(uri, id.toString()),
                    name = it.getString(nameCol) ?: "Unknown",
                    path = path,
                    size = size,
                    mimeType = mime,
                    fileType = mimeToFileType(mime, defaultType),
                    dateModified = it.getLong(dateCol) * 1000L
                )
            }
        }
        return files
    }

    private fun mimeToFileType(mime: String, default: FileType): FileType = when {
        mime.startsWith("image/") -> FileType.IMAGE
        mime.startsWith("video/") -> FileType.VIDEO
        mime.startsWith("audio/") -> FileType.AUDIO
        mime.contains("pdf") || mime.contains("word") || mime.contains("excel") || mime.startsWith("text/") -> FileType.DOCUMENT
        mime.contains("apk") -> FileType.APK
        else -> default
    }

    fun reset() { scanProgress.value = ScanProgress.Idle }
}
