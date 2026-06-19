package com.storagecleaner.ui.duplicates

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.navArgs
import com.bumptech.glide.Glide
import com.storagecleaner.data.model.ScannedFile
import com.storagecleaner.databinding.FragmentComparisonBinding
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.*

/**
 * Side-by-side image comparison (v6-lite §8).
 *
 * Phase 2 note: pinch-zoom / pan / swipe-to-compare are deferred — this screen
 * shows a static side-by-side view with a full comparison table, which covers
 * the core decision-making need without adding a custom touch-gesture surface.
 */
@AndroidEntryPoint
class ComparisonFragment : Fragment() {

    private var _binding: FragmentComparisonBinding? = null
    private val binding get() = _binding!!
    private val args: ComparisonFragmentArgs by navArgs()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentComparisonBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val a = args.fileA
        val b = args.fileB

        Glide.with(this).load(a.uri).centerCrop().into(binding.ivImageA)
        Glide.with(this).load(b.uri).centerCrop().into(binding.ivImageB)

        binding.tvLabelA.text = "KEEP\n${a.name}"
        binding.tvLabelB.text = "COPY\n${b.name}"

        binding.tvSimilarityHeader.text = "${args.similarityPct}% Similar"

        val df = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())

        fillRow(binding.rowResolution, "Resolution",
            resolutionLabel(a), resolutionLabel(b))
        fillRow(binding.rowSize, "File Size", formatSize(a.size), formatSize(b.size))
        fillRow(binding.rowDate, "Date Modified", df.format(Date(a.dateModified)), df.format(Date(b.dateModified)))
        fillRow(binding.rowFolder, "Folder",
            java.io.File(a.path).parent?.let { java.io.File(it).name } ?: "—",
            java.io.File(b.path).parent?.let { java.io.File(it).name } ?: "—")
    }

    private fun fillRow(row: android.widget.LinearLayout, label: String, valueA: String, valueB: String) {
        val tvLabel = row.getChildAt(0) as android.widget.TextView
        val tvA = row.getChildAt(1) as android.widget.TextView
        val tvB = row.getChildAt(2) as android.widget.TextView
        tvLabel.text = label
        tvA.text = valueA
        tvB.text = valueB
    }

    private fun resolutionLabel(f: ScannedFile) = if (f.width > 0 && f.height > 0) "${f.width}×${f.height}" else "—"

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "%.2f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576     -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024         -> "%.0f KB".format(bytes / 1_024.0)
        else                   -> "$bytes B"
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
