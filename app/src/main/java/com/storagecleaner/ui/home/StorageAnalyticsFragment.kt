package com.storagecleaner.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.storagecleaner.R
import com.storagecleaner.data.model.FileType
import com.storagecleaner.data.model.LargestFileEntry
import com.storagecleaner.data.model.LargestFolderEntry
import com.storagecleaner.data.repository.StorageRepository
import com.storagecleaner.databinding.FragmentStorageAnalyticsBinding
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

/**
 * Storage Analytics screen (v6-lite §18).
 * Surfaces the top 100 largest individual files and top 50 largest
 * folders, derived from MediaStore aggregates (no filesystem walk —
 * keeps this fast and safe even on large libraries).
 */
enum class AnalyticsView { FILES, FOLDERS }

@HiltViewModel
class StorageAnalyticsViewModel @Inject constructor(
    private val repository: StorageRepository
) : ViewModel() {

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading

    private val _view = MutableStateFlow(AnalyticsView.FILES)
    val view: StateFlow<AnalyticsView> = _view

    private val _largestFiles = MutableStateFlow<List<LargestFileEntry>>(emptyList())
    val largestFiles: StateFlow<List<LargestFileEntry>> = _largestFiles

    private val _largestFolders = MutableStateFlow<List<LargestFolderEntry>>(emptyList())
    val largestFolders: StateFlow<List<LargestFolderEntry>> = _largestFolders

    init { load() }

    fun setView(v: AnalyticsView) { _view.value = v }

    fun load() {
        viewModelScope.launch {
            _loading.value = true
            _largestFiles.value = repository.getLargestFiles(100)
            _largestFolders.value = repository.getLargestFolders(50)
            _loading.value = false
        }
    }

    fun formatSize(bytes: Long) = repository.formatSize(bytes)
}

@AndroidEntryPoint
class StorageAnalyticsFragment : Fragment() {

    private var _binding: FragmentStorageAnalyticsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: StorageAnalyticsViewModel by viewModels()
    private lateinit var filesAdapter: LargestFilesAdapter
    private lateinit var foldersAdapter: LargestFoldersAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentStorageAnalyticsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        filesAdapter = LargestFilesAdapter { viewModel.formatSize(it) }
        foldersAdapter = LargestFoldersAdapter { viewModel.formatSize(it) }

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())

        val chipFiles = Chip(requireContext()).apply {
            text = "📄 Largest Files"; isCheckable = true; isChecked = true
        }
        val chipFolders = Chip(requireContext()).apply {
            text = "📁 Largest Folders"; isCheckable = true
        }
        binding.chipGroup.addView(chipFiles)
        binding.chipGroup.addView(chipFolders)
        chipFiles.setOnClickListener { viewModel.setView(AnalyticsView.FILES) }
        chipFolders.setOnClickListener { viewModel.setView(AnalyticsView.FOLDERS) }

        binding.swipeRefresh.setOnRefreshListener { viewModel.load() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.view.collect { view ->
                chipFiles.isChecked = view == AnalyticsView.FILES
                chipFolders.isChecked = view == AnalyticsView.FOLDERS
                binding.recyclerView.adapter = if (view == AnalyticsView.FILES) filesAdapter else foldersAdapter
                updateEmptyState()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.loading.collect { loading ->
                binding.swipeRefresh.isRefreshing = loading
                if (!loading) updateEmptyState()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.largestFiles.collect { files ->
                filesAdapter.submitList(files)
                if (viewModel.view.value == AnalyticsView.FILES) updateEmptyState()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.largestFolders.collect { folders ->
                foldersAdapter.submitList(folders)
                if (viewModel.view.value == AnalyticsView.FOLDERS) updateEmptyState()
            }
        }
    }

    private fun updateEmptyState() {
        if (viewModel.loading.value) {
            binding.tvEmpty.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
            return
        }
        val isEmpty = when (viewModel.view.value) {
            AnalyticsView.FILES -> viewModel.largestFiles.value.isEmpty()
            AnalyticsView.FOLDERS -> viewModel.largestFolders.value.isEmpty()
        }
        binding.tvEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}

// ════════════════════════════════════════════════════════════════════════════
class LargestFilesAdapter(
    private val formatSize: (Long) -> String
) : RecyclerView.Adapter<LargestFilesAdapter.VH>() {

    private var items: List<LargestFileEntry> = emptyList()

    fun submitList(list: List<LargestFileEntry>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_largest_file, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position], position)

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val tvRank: TextView = v.findViewById(R.id.tvRank)
        private val ivIcon: ImageView = v.findViewById(R.id.ivIcon)
        private val tvName: TextView = v.findViewById(R.id.tvFileName)
        private val tvPath: TextView = v.findViewById(R.id.tvFilePath)
        private val tvSize: TextView = v.findViewById(R.id.tvFileSize)

        fun bind(entry: LargestFileEntry, position: Int) {
            tvRank.text = "${position + 1}"
            ivIcon.setImageResource(when (entry.fileType) {
                FileType.IMAGE -> R.drawable.ic_image_placeholder
                FileType.VIDEO -> R.drawable.ic_video
                FileType.AUDIO -> R.drawable.ic_audio
                FileType.DOCUMENT -> R.drawable.ic_document
                FileType.APK -> R.drawable.ic_apk
                else -> R.drawable.ic_file
            })
            tvName.text = entry.name
            tvPath.text = entry.path
            tvSize.text = formatSize(entry.size)
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
class LargestFoldersAdapter(
    private val formatSize: (Long) -> String
) : RecyclerView.Adapter<LargestFoldersAdapter.VH>() {

    private var items: List<LargestFolderEntry> = emptyList()

    fun submitList(list: List<LargestFolderEntry>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_largest_folder, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position], position)

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val tvRank: TextView = v.findViewById(R.id.tvRank)
        private val tvName: TextView = v.findViewById(R.id.tvFolderName)
        private val tvPath: TextView = v.findViewById(R.id.tvFolderPath)
        private val tvMeta: TextView = v.findViewById(R.id.tvFolderMeta)

        fun bind(entry: LargestFolderEntry, position: Int) {
            tvRank.text = "${position + 1}"
            tvName.text = "📁 ${entry.name}"
            tvPath.text = entry.path
            tvMeta.text = "${formatSize(entry.totalBytes)} · ${entry.fileCount} file(s)"
        }
    }
}
