package com.storagecleaner.ui.archive

import android.content.Intent
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
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.storagecleaner.R
import com.storagecleaner.data.model.ArchivedFile
import com.storagecleaner.data.model.FileType
import com.storagecleaner.data.repository.StorageRepository
import com.storagecleaner.databinding.FragmentArchiveBinding
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

// ════════════════════════════════════════════════════════════════════════════
@HiltViewModel
class ArchiveViewModel @Inject constructor(
    private val repository: StorageRepository
) : ViewModel() {

    val archivedFiles: StateFlow<List<ArchivedFile>> = repository.getArchivedFiles()
        .let { flow ->
            val state = MutableStateFlow<List<ArchivedFile>>(emptyList())
            viewModelScope.launch { flow.collect { state.value = it } }
            state
        }

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun restore(files: List<ArchivedFile>) {
        viewModelScope.launch {
            _busy.value = true
            val result = repository.restoreArchivedFiles(files)
            _busy.value = false
            _message.value = if (result.failed == 0) "✅ Restored ${result.succeeded} file(s)"
            else "Restored ${result.succeeded}, failed ${result.failed}"
        }
    }

    fun removeRecord(file: ArchivedFile) {
        viewModelScope.launch { repository.deleteArchivedEntry(file) }
    }

    fun consumeMessage() { _message.value = null }
    fun formatSize(bytes: Long) = repository.formatSize(bytes)
    fun getArchivalRootPath() = repository.getArchivalRoot().absolutePath
}

// ════════════════════════════════════════════════════════════════════════════
sealed class ArchiveListItem {
    data class SessionHeader(val folderName: String, val fileCount: Int, val totalBytes: Long) : ArchiveListItem()
    data class FileRow(val file: ArchivedFile) : ArchiveListItem()
}

@AndroidEntryPoint
class ArchiveFragment : Fragment() {

    private var _binding: FragmentArchiveBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ArchiveViewModel by viewModels()
    private lateinit var adapter: ArchiveAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentArchiveBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = ArchiveAdapter(
            onRestore = { file ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Restore file?")
                    .setMessage("Restore \"${file.name}\" to its original folder?")
                    .setPositiveButton("Restore") { _, _ -> viewModel.restore(listOf(file)) }
                    .setNegativeButton("Cancel", null).show()
            },
            onRemoveRecord = { file ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Remove record?")
                    .setMessage("This only removes the archive record — the file in Documents/StorageCleaner stays untouched.")
                    .setPositiveButton("Remove") { _, _ -> viewModel.removeRecord(file) }
                    .setNegativeButton("Cancel", null).show()
            },
            onOpenFolder = { folderName -> openFolder(folderName) },
            onRestoreFolder = { files ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Restore all?")
                    .setMessage("Restore all ${files.size} file(s) in this session to their original folders?")
                    .setPositiveButton("Restore All") { _, _ -> viewModel.restore(files) }
                    .setNegativeButton("Cancel", null).show()
            },
            formatSize = { viewModel.formatSize(it) }
        )

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@ArchiveFragment.adapter
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.archivedFiles.collectLatest { files ->
                bindFiles(files)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.busy.collectLatest { binding.progressBar.visibility = if (it) View.VISIBLE else View.GONE }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.message.collectLatest { msg ->
                if (msg != null) {
                    Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
                    viewModel.consumeMessage()
                }
            }
        }
    }

    private fun bindFiles(files: List<ArchivedFile>) {
        binding.tvEmpty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE

        binding.tvTotalArchived.text = "${files.size} file(s) · ${viewModel.formatSize(files.sumOf { it.size })} archived"

        val grouped = files.groupBy { it.archiveFolder }.toSortedMap(compareByDescending { it })
        val list = mutableListOf<ArchiveListItem>()
        grouped.forEach { (folder, items) ->
            list += ArchiveListItem.SessionHeader(folder, items.size, items.sumOf { it.size })
            items.forEach { list += ArchiveListItem.FileRow(it) }
        }
        adapter.submitList(list, grouped)
    }

    private fun openFolder(folderName: String) {
        try {
            val dir = java.io.File(viewModel.getArchivalRootPath(), folderName)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                requireContext(), "${requireContext().packageName}.fileprovider", dir
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "resource/folder")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Snackbar.make(binding.root, "No file manager app found. Files are in Documents/StorageCleaner/$folderName", Snackbar.LENGTH_LONG).show()
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}

// ════════════════════════════════════════════════════════════════════════════
class ArchiveAdapter(
    private val onRestore: (ArchivedFile) -> Unit,
    private val onRemoveRecord: (ArchivedFile) -> Unit,
    private val onOpenFolder: (String) -> Unit,
    private val onRestoreFolder: (List<ArchivedFile>) -> Unit,
    private val formatSize: (Long) -> String
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<ArchiveListItem> = emptyList()
    private var grouped: Map<String, List<ArchivedFile>> = emptyMap()

    fun submitList(list: List<ArchiveListItem>, grouped: Map<String, List<ArchivedFile>>) {
        items = list; this.grouped = grouped; notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int) = when (items[position]) {
        is ArchiveListItem.SessionHeader -> 0
        is ArchiveListItem.FileRow -> 1
    }
    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 0) SessionVH(inflater.inflate(R.layout.item_archive_session, parent, false))
        else FileVH(inflater.inflate(R.layout.item_archived_file, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is ArchiveListItem.SessionHeader -> (holder as SessionVH).bind(item)
            is ArchiveListItem.FileRow -> (holder as FileVH).bind(item.file)
        }
    }

    inner class SessionVH(v: View) : RecyclerView.ViewHolder(v) {
        private val tvFolder: TextView = v.findViewById(R.id.tvSessionFolder)
        private val tvMeta: TextView = v.findViewById(R.id.tvSessionMeta)
        private val btnOpen: TextView = v.findViewById(R.id.btnOpenFolder)
        private val btnRestoreAll: TextView = v.findViewById(R.id.btnRestoreAll)

        fun bind(header: ArchiveListItem.SessionHeader) {
            tvFolder.text = "📦 ${header.folderName}"
            tvMeta.text = "${header.fileCount} file(s) · ${formatSize(header.totalBytes)}"
            btnOpen.setOnClickListener { onOpenFolder(header.folderName) }
            btnRestoreAll.setOnClickListener { grouped[header.folderName]?.let { onRestoreFolder(it) } }
        }
    }

    inner class FileVH(v: View) : RecyclerView.ViewHolder(v) {
        private val ivThumb: ImageView = v.findViewById(R.id.ivThumbnail)
        private val tvName: TextView = v.findViewById(R.id.tvFileName)
        private val tvDetails: TextView = v.findViewById(R.id.tvFileDetails)
        private val btnRestore: TextView = v.findViewById(R.id.btnRestore)
        private val btnRemove: TextView = v.findViewById(R.id.btnRemove)

        fun bind(file: ArchivedFile) {
            tvName.text = file.name
            val df = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
            tvDetails.text = "${formatSize(file.size)} · Archived ${df.format(Date(file.archivedAt))}"

            val type = runCatching { FileType.valueOf(file.fileType) }.getOrDefault(FileType.OTHER)
            if (type == FileType.IMAGE || type == FileType.VIDEO) {
                Glide.with(itemView.context)
                    .load(java.io.File(file.archivedPath))
                    .placeholder(R.drawable.ic_image_placeholder)
                    .centerCrop().into(ivThumb)
            } else {
                ivThumb.setImageResource(when (type) {
                    FileType.DOCUMENT -> R.drawable.ic_document
                    FileType.AUDIO -> R.drawable.ic_audio
                    else -> R.drawable.ic_file
                })
            }

            btnRestore.setOnClickListener { onRestore(file) }
            btnRemove.setOnClickListener { onRemoveRecord(file) }
        }
    }
}
