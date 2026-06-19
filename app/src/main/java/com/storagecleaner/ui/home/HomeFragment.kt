package com.storagecleaner.ui.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.storagecleaner.R
import com.storagecleaner.data.model.StorageDashboardData
import com.storagecleaner.data.repository.StorageRepository
import com.storagecleaner.databinding.FragmentHomeBinding
import com.storagecleaner.util.StorageStatsHelper
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

// ════════════════════════════════════════════════════════════════════════════
//  ViewModel
// ════════════════════════════════════════════════════════════════════════════
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: StorageRepository,
    private val statsHelper: StorageStatsHelper
) : ViewModel() {

    private val _dashboard = MutableStateFlow(StorageDashboardData())
    val dashboard: StateFlow<StorageDashboardData> = _dashboard

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading

    fun load() {
        viewModelScope.launch {
            _loading.value = true
            _dashboard.value = repository.getDashboardData()
            _loading.value = false
        }
    }

    fun formatSize(bytes: Long) = statsHelper.formatSize(bytes)
}

// ════════════════════════════════════════════════════════════════════════════
//  Fragment
// ════════════════════════════════════════════════════════════════════════════
@AndroidEntryPoint
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: HomeViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            findNavController().navigate(R.id.action_home_to_folderPicker)
        } else {
            showPermissionRationale()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnStartScan.setOnClickListener { checkPermissionsAndNavigate() }
        binding.btnViewDuplicates.setOnClickListener { findNavController().navigate(R.id.action_home_to_folderPicker) }
        binding.btnViewArchive.setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("Coming Soon")
                .setMessage("The Archival function will be available in Phase 2. Coming Soon")
                .setPositiveButton("Close", null)
                .show()
        }
        binding.btnScanHistory.setOnClickListener { findNavController().navigate(R.id.action_home_to_scanHistory) }
        binding.btnStorageAnalytics.setOnClickListener { findNavController().navigate(R.id.action_home_to_analytics) }

        binding.swipeRefresh.setOnRefreshListener {
            viewModel.load()
        }

        observeViewModel()
        viewModel.load()
    }

    override fun onResume() {
        super.onResume()
        viewModel.load()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.loading.collectLatest { loading ->
                binding.swipeRefresh.isRefreshing = loading
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.dashboard.collectLatest { data ->
                bindDashboard(data)
            }
        }
    }

    private fun bindDashboard(data: StorageDashboardData) {
        binding.tvUsedStorage.text = viewModel.formatSize(data.usedBytes)
        binding.tvTotalStorage.text = "of ${viewModel.formatSize(data.totalBytes)}"
        binding.tvFreeStorage.text = "${viewModel.formatSize(data.freeBytes)} free"
        binding.tvPotentialRecovery.text = viewModel.formatSize(data.potentialRecoveryBytes)

        val usedPct = if (data.totalBytes > 0)
            (data.usedBytes * 100 / data.totalBytes).toInt().coerceIn(0, 100) else 0
        binding.storageProgress.progress = usedPct
        binding.tvUsedPct.text = "$usedPct% used"

        binding.tvLastScan.text = if (data.lastScanAt != null) {
            val df = java.text.SimpleDateFormat("dd MMM yyyy, HH:mm", java.util.Locale.getDefault())
            "Last scan: ${df.format(java.util.Date(data.lastScanAt))}"
        } else "No scans yet"

        // Breakdown bars
        binding.breakdownContainer.removeAllViews()
        val maxBytes = data.breakdown.maxOfOrNull { it.bytes }?.coerceAtLeast(1L) ?: 1L
        data.breakdown.filter { it.bytes > 0 }.forEach { item ->
            val row = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_storage_breakdown, binding.breakdownContainer, false)
            val tvLabel = row.findViewById<android.widget.TextView>(R.id.tvBreakdownLabel)
            val tvSize  = row.findViewById<android.widget.TextView>(R.id.tvBreakdownSize)
            val bar     = row.findViewById<android.widget.ProgressBar>(R.id.breakdownBar)
            tvLabel.text = "${item.emoji} ${item.label}"
            tvSize.text  = viewModel.formatSize(item.bytes)
            bar.max = maxBytes.toInt().coerceAtLeast(1)
            bar.progress = item.bytes.toInt().coerceAtMost(bar.max)
            binding.breakdownContainer.addView(row)
        }

        binding.btnViewDuplicates.text =
            if (data.potentialRecoveryBytes > 0)
                "🔍 View Duplicates (${viewModel.formatSize(data.potentialRecoveryBytes)} recoverable)"
            else "🔍 View Duplicates"
    }

    private fun checkPermissionsAndNavigate() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            findNavController().navigate(R.id.action_home_to_folderPicker)
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun showPermissionRationale() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Storage Permission Required")
            .setMessage("StorageCleaner needs access to your photos and files to find duplicates.")
            .setPositiveButton("Open Settings") { _, _ ->
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", requireActivity().packageName, null)
                })
            }
            .setNegativeButton("Cancel", null).show()
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
