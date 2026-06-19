package com.storagecleaner.ui.protected_files

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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.storagecleaner.R
import com.storagecleaner.data.model.ProtectedFile
import com.storagecleaner.data.repository.StorageRepository
import com.storagecleaner.databinding.FragmentProtectedFilesBinding
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

/**
 * Protected Files screen (v6-lite §12).
 * Files marked "Never suggest again" are excluded from future scan
 * recommendations and from Smart Select — manage / remove them here.
 */
@HiltViewModel
class ProtectedFilesViewModel @Inject constructor(
    private val repository: StorageRepository
) : ViewModel() {

    val protectedFiles: StateFlow<List<ProtectedFile>> = run {
        val state = MutableStateFlow<List<ProtectedFile>>(emptyList())
        viewModelScope.launch { repository.getProtectedFiles().collect { state.value = it } }
        state
    }

    fun unprotect(file: ProtectedFile) {
        viewModelScope.launch { repository.unprotectFile(file.uri) }
    }
}

@AndroidEntryPoint
class ProtectedFilesFragment : Fragment() {

    private var _binding: FragmentProtectedFilesBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ProtectedFilesViewModel by viewModels()
    private lateinit var adapter: ProtectedFilesAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentProtectedFilesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = ProtectedFilesAdapter { file ->
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Remove protection?")
                .setMessage("\"${file.name}\" will be eligible for duplicate recommendations and Smart Select again.")
                .setPositiveButton("Remove") { _, _ ->
                    viewModel.unprotect(file)
                    Snackbar.make(binding.root, "Protection removed for \"${file.name}\"", Snackbar.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null).show()
        }

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@ProtectedFilesFragment.adapter
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.protectedFiles.collect { files ->
                binding.tvEmpty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
                binding.recyclerView.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE
                binding.tvCount.text = "${files.size} protected file(s)"
                adapter.submitList(files)
            }
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}

class ProtectedFilesAdapter(
    private val onUnprotect: (ProtectedFile) -> Unit
) : RecyclerView.Adapter<ProtectedFilesAdapter.VH>() {

    private var items: List<ProtectedFile> = emptyList()

    fun submitList(list: List<ProtectedFile>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_protected_file, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val ivIcon: ImageView = v.findViewById(R.id.ivIcon)
        private val tvName: TextView = v.findViewById(R.id.tvFileName)
        private val tvPath: TextView = v.findViewById(R.id.tvFilePath)
        private val tvAdded: TextView = v.findViewById(R.id.tvAddedAt)
        private val btnUnprotect: ImageView = v.findViewById(R.id.btnUnprotect)

        fun bind(file: ProtectedFile) {
            ivIcon.setImageResource(R.drawable.ic_shield)
            tvName.text = file.name
            tvPath.text = file.path.ifEmpty { file.uri }
            val df = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
            tvAdded.text = "Protected ${df.format(Date(file.addedAt))}"
            btnUnprotect.setOnClickListener { onUnprotect(file) }
        }
    }
}
