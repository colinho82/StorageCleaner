package com.storagecleaner.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.storagecleaner.R
import com.storagecleaner.data.model.IgnoreRule
import com.storagecleaner.data.repository.StorageRepository
import com.storagecleaner.databinding.FragmentSettingsBinding
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Settings screen (v6-lite §17).
 * Covers Detection thresholds (§17 "Detection Settings"), Ignore Rules
 * (§13), a link to Protected Files (§12), and notification permission
 * status (§16). Performance settings (battery saver, max CPU, background
 * processing) are part of the Phase 2 roadmap and intentionally omitted
 * here to avoid adding WorkManager scheduling complexity to this build.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: StorageRepository
) : ViewModel() {

    private val _imageThresholdPct = MutableStateFlow((repository.getImageSimilarityThreshold() * 100).toInt())
    val imageThresholdPct: StateFlow<Int> = _imageThresholdPct

    private val _documentThresholdPct = MutableStateFlow((repository.getDocumentSimilarityThreshold() * 100).toInt())
    val documentThresholdPct: StateFlow<Int> = _documentThresholdPct

    val ignoreRules: StateFlow<List<IgnoreRule>> = run {
        val state = MutableStateFlow<List<IgnoreRule>>(emptyList())
        viewModelScope.launch { repository.getIgnoreRules().collect { state.value = it } }
        state
    }

    init {
        viewModelScope.launch { repository.seedDefaultIgnoreRulesIfEmpty() }
    }

    fun setImageThreshold(pct: Int) {
        _imageThresholdPct.value = pct
        repository.setImageSimilarityThreshold(pct / 100.0)
    }

    fun setDocumentThreshold(pct: Int) {
        _documentThresholdPct.value = pct
        repository.setDocumentSimilarityThreshold(pct / 100.0)
    }

    fun toggleIgnoreRule(rule: IgnoreRule, enabled: Boolean) {
        viewModelScope.launch { repository.updateIgnoreRule(rule.copy(enabled = enabled)) }
    }

    fun deleteIgnoreRule(rule: IgnoreRule) {
        viewModelScope.launch { repository.deleteIgnoreRule(rule) }
    }

    fun addFolderIgnoreRule(path: String, label: String) {
        viewModelScope.launch {
            repository.addIgnoreRule(IgnoreRule(ruleType = "FOLDER", value = path, label = label, enabled = true))
        }
    }

    fun getArchivalRootPath(): String = repository.getArchivalRoot().absolutePath

    /** Whether "All Files Access" (MANAGE_EXTERNAL_STORAGE) is granted — required on
     *  Android 11+ to write archived files into the public Documents/StorageCleaner folder. */
    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
}

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SettingsViewModel by viewModels()
    private lateinit var ignoreRuleAdapter: IgnoreRuleAdapter

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshNotificationStatus() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Detection thresholds ---------------------------------------------
        binding.sliderImageThreshold.value = viewModel.imageThresholdPct.value.toFloat()
        binding.tvImageThresholdValue.text = "${viewModel.imageThresholdPct.value}%"
        binding.sliderImageThreshold.addOnChangeListener { _, value, _ ->
            val pct = value.toInt()
            binding.tvImageThresholdValue.text = "$pct%"
            viewModel.setImageThreshold(pct)
        }

        binding.sliderDocThreshold.value = viewModel.documentThresholdPct.value.toFloat()
        binding.tvDocThresholdValue.text = "${viewModel.documentThresholdPct.value}%"
        binding.sliderDocThreshold.addOnChangeListener { _, value, _ ->
            val pct = value.toInt()
            binding.tvDocThresholdValue.text = "$pct%"
            viewModel.setDocumentThreshold(pct)
        }

        // Protected Files ----------------------------------------------------
        binding.rowProtectedFiles.setOnClickListener {
            findNavController().navigate(R.id.action_settings_to_protectedFiles)
        }

        // Notifications -------------------------------------------------------
        refreshNotificationStatus()
        binding.btnEnableNotifications.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Archive info ----------------------------------------------------------
        binding.tvArchiveLocation.text = "📁 ${viewModel.getArchivalRootPath()}"
        refreshArchiveAccessStatus()
        binding.btnGrantAllFilesAccess.setOnClickListener {
            try {
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${requireContext().packageName}")
                }
                startActivity(intent)
            } catch (e: Exception) {
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }

        // Ignore rules ------------------------------------------------------------
        ignoreRuleAdapter = IgnoreRuleAdapter(
            onToggle = { rule, enabled -> viewModel.toggleIgnoreRule(rule, enabled) },
            onDelete = { rule ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Remove rule?")
                    .setMessage("\"${rule.label}\" will no longer be excluded from scans.")
                    .setPositiveButton("Remove") { _, _ -> viewModel.deleteIgnoreRule(rule) }
                    .setNegativeButton("Cancel", null).show()
            }
        )
        binding.recyclerIgnoreRules.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = ignoreRuleAdapter
            isNestedScrollingEnabled = false
        }

        binding.btnAddIgnoreRule.setOnClickListener { showAddFolderDialog() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.ignoreRules.collect { rules -> ignoreRuleAdapter.submitList(rules) }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshArchiveAccessStatus()
    }

    private fun refreshArchiveAccessStatus() {
        val granted = viewModel.hasAllFilesAccess()
        binding.tvArchiveAccessWarning.visibility = if (granted) View.GONE else View.VISIBLE
        binding.btnGrantAllFilesAccess.visibility = if (granted) View.GONE else View.VISIBLE
    }

    private fun refreshNotificationStatus() {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true

        binding.tvNotificationStatus.text = if (granted)
            "✅ Notifications enabled — you'll be alerted when scans, archives, and restores complete."
        else
            "🔔 Enable notifications to get alerts when scans, archives, and restores complete."
        binding.btnEnableNotifications.visibility = if (granted) View.GONE else View.VISIBLE
    }

    private fun showAddFolderDialog() {
        val input = EditText(requireContext()).apply {
            hint = "/storage/emulated/0/Pictures/Example"
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(32, 16, 32, 16)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Add folder to ignore")
            .setMessage("Files inside this folder (and its subfolders) will be skipped during scans.")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val path = input.text.toString().trim()
                if (path.isNotEmpty()) {
                    val label = path.substringAfterLast('/')
                    viewModel.addFolderIgnoreRule(path, label.ifEmpty { path })
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}

// ════════════════════════════════════════════════════════════════════════════
class IgnoreRuleAdapter(
    private val onToggle: (IgnoreRule, Boolean) -> Unit,
    private val onDelete: (IgnoreRule) -> Unit
) : RecyclerView.Adapter<IgnoreRuleAdapter.VH>() {

    private var items: List<IgnoreRule> = emptyList()

    fun submitList(list: List<IgnoreRule>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_ignore_rule, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val tvLabel: TextView = v.findViewById(R.id.tvRuleLabel)
        private val tvValue: TextView = v.findViewById(R.id.tvRuleValue)
        private val switchEnabled: SwitchMaterial = v.findViewById(R.id.switchEnabled)
        private val btnDelete: TextView = v.findViewById(R.id.btnDelete)

        fun bind(rule: IgnoreRule) {
            tvLabel.text = rule.label
            tvValue.text = rule.value
            switchEnabled.setOnCheckedChangeListener(null)
            switchEnabled.isChecked = rule.enabled
            switchEnabled.setOnCheckedChangeListener { _, checked -> onToggle(rule, checked) }
            btnDelete.setOnClickListener { onDelete(rule) }
        }
    }
}
