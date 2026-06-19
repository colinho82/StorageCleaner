package com.storagecleaner.util

import com.storagecleaner.data.model.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Determines which file in a duplicate group should be KEPT, and produces
 * short human-readable reasons (v6-lite §6 "Smart Recommendation Engine").
 *
 * No ML required — purely rule-based on metadata already collected during scan:
 *   - resolution (images/video)
 *   - file size
 *   - date modified (recency)
 *   - originating folder (camera folders > messaging folders)
 */
@Singleton
class RecommendationEngine @Inject constructor() {

    /** Folders considered "lower priority" sources (messaging apps, caches) */
    private val lowPriorityFolderHints = listOf(
        "whatsapp", "telegram", "messenger", ".thumbnails", "cache", "download"
    )

    /**
     * Returns the index of the recommended "keep" file and a list of reasons.
     */
    fun recommend(files: List<ScannedFile>): Pair<Int, List<String>> {
        if (files.size <= 1) return Pair(0, emptyList())

        // Score each file — higher is better
        val scores = files.map { file -> scoreFile(file, files) }
        val bestIndex = scores.indices.maxByOrNull { scores[it].total } ?: 0
        return Pair(bestIndex, scores[bestIndex].reasons)
    }

    private data class FileScore(val total: Double, val reasons: List<String>)

    private fun scoreFile(file: ScannedFile, allFiles: List<ScannedFile>): FileScore {
        val reasons = mutableListOf<String>()
        var score = 0.0

        // ── Resolution (images/video) ─────────────────────────────────────
        val maxRes = allFiles.maxOf { it.resolutionPixels }
        if (file.resolutionPixels > 0 && file.resolutionPixels == maxRes && maxRes > 0) {
            val others = allFiles.count { it.resolutionPixels < maxRes }
            if (others > 0) {
                score += 30
                reasons += "Highest resolution"
            }
        }

        // ── File size ──────────────────────────────────────────────────────
        val maxSize = allFiles.maxOf { it.size }
        if (file.size == maxSize) {
            val others = allFiles.count { it.size < maxSize }
            if (others > 0) {
                score += 25
                reasons += "Largest file size"
            }
        }

        // ── Recency ────────────────────────────────────────────────────────
        val maxDate = allFiles.maxOf { it.dateModified }
        if (file.dateModified == maxDate) {
            val others = allFiles.count { it.dateModified < maxDate }
            if (others > 0) {
                score += 20
                reasons += "Most recent version"
            }
        }

        // ── Folder priority ────────────────────────────────────────────────
        val pathLower = file.path.lowercase()
        val isLowPriority = lowPriorityFolderHints.any { pathLower.contains(it) }
        val anyOtherIsLowPriority = allFiles.any { other ->
            other !== file && lowPriorityFolderHints.any { hint -> other.path.lowercase().contains(hint) }
        }
        if (!isLowPriority && anyOtherIsLowPriority) {
            score += 15
            reasons += "Original camera/storage folder"
        }

        // ── Filename quality (camera-style names rank higher than "Copy of…") ─
        val nameLower = file.name.lowercase()
        val looksLikeCopy = nameLower.contains("copy") || nameLower.contains("(1)") ||
                nameLower.contains("(2)") || nameLower.startsWith("img-") && nameLower.contains("wa")
        if (!looksLikeCopy) {
            score += 10
        } else {
            reasons.removeAll { it == "Original camera/storage folder" } // avoid double-claiming
        }

        // ── Metadata completeness (has resolution info at all) ────────────
        if (file.width > 0 && file.height > 0) {
            score += 5
            if (!reasons.contains("Highest resolution") && allFiles.any { it.width == 0 }) {
                reasons += "Metadata complete"
            }
        }

        if (reasons.isEmpty()) reasons += "Default selection"

        return FileScore(score, reasons.distinct())
    }
}
