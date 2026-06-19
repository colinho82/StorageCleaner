package com.storagecleaner.ui.preview

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.navigation.fragment.navArgs
import com.bumptech.glide.Glide
import com.storagecleaner.data.model.FileType
import com.storagecleaner.data.model.ScannedFile
import com.storagecleaner.data.repository.StorageRepository
import com.storagecleaner.databinding.FragmentFilePreviewBinding
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@HiltViewModel
class FilePreviewViewModel @Inject constructor(
    private val repository: StorageRepository
) : ViewModel() {
    private val _isProtected = MutableStateFlow(false)
    val isProtected: StateFlow<Boolean> = _isProtected

    fun checkProtected(uri: String) {
        viewModelScope.launch { _isProtected.value = repository.isProtected(uri) }
    }

    fun toggleProtect(file: ScannedFile) {
        viewModelScope.launch {
            val uri = file.uri.toString()
            if (_isProtected.value) repository.unprotectFile(uri) else repository.protectFile(file)
            _isProtected.value = !_isProtected.value
        }
    }

    fun formatSize(bytes: Long) = repository.formatSize(bytes)
}

@AndroidEntryPoint
class FilePreviewFragment : Fragment() {

    private var _binding: FragmentFilePreviewBinding? = null
    private val binding get() = _binding!!
    private val args: FilePreviewFragmentArgs by navArgs()
    private val viewModel: FilePreviewViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFilePreviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val file = args.file

        binding.tvFileName.text = file.name
        binding.tvFilePath.text = file.path.ifEmpty { file.uri.toString() }

        val df = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
        val resPart = if (file.width > 0 && file.height > 0) "${file.width}×${file.height} · " else ""
        binding.tvFileDetails.text = "${viewModel.formatSize(file.size)} · ${resPart}Modified ${df.format(Date(file.dateModified))}"
        binding.tvMimeType.text = file.mimeType

        when (file.fileType) {
            FileType.IMAGE -> {
                binding.ivPreview.visibility = View.VISIBLE
                binding.ivIcon.visibility = View.GONE
                Glide.with(this).load(file.uri).into(binding.ivPreview)
            }
            else -> {
                binding.ivPreview.visibility = View.GONE
                binding.ivIcon.visibility = View.VISIBLE
                binding.ivIcon.setImageResource(when (file.fileType) {
                    FileType.VIDEO -> com.storagecleaner.R.drawable.ic_video
                    FileType.AUDIO -> com.storagecleaner.R.drawable.ic_audio
                    FileType.DOCUMENT -> com.storagecleaner.R.drawable.ic_document
                    FileType.APK -> com.storagecleaner.R.drawable.ic_apk
                    else -> com.storagecleaner.R.drawable.ic_file
                })
            }
        }

        binding.btnOpen.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(file.uri, file.mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (e: Exception) {
                com.google.android.material.snackbar.Snackbar.make(binding.root, "No app found to open this file", com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
            }
        }

        binding.btnShare.setOnClickListener {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = file.mimeType
                putExtra(Intent.EXTRA_STREAM, file.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share via"))
        }

        viewModel.checkProtected(file.uri.toString())
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isProtected.collect { protected ->
                binding.btnProtect.text = if (protected) "🛡️ Protected (tap to remove)" else "🛡️ Mark as Protected"
            }
        }
        binding.btnProtect.setOnClickListener { viewModel.toggleProtect(file) }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
