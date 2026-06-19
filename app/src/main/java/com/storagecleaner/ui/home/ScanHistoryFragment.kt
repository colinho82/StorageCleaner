package com.storagecleaner.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.storagecleaner.R
import com.storagecleaner.data.model.ScanSession
import com.storagecleaner.data.repository.StorageRepository
import com.storagecleaner.databinding.FragmentScanHistoryBinding
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

/**
 * Scan History screen (v6-lite §15).
 * Shows every completed scan: when it ran, how long it took, what was
 * found, and how much storage was actually reclaimed afterwards.
 */
@HiltViewModel
class ScanHistoryViewModel @Inject constructor(
    private val repository: StorageRepository
) : ViewModel() {

    val sessions: StateFlow<List<ScanSession>> = run {
        val state = MutableStateFlow<List<ScanSession>>(emptyList())
        viewModelScope.launch { repository.getScanHistory().collect { state.value = it } }
        state
    }

    private val _totalReclaimed = MutableStateFlow(0L)
    val totalReclaimed: StateFlow<Long> = _totalReclaimed

    init {
        viewModelScope.launch {
            sessions.collect { _totalReclaimed.value = repository.totalReclaimedAllTime() }
        }
    }

    fun clearHistory() {
        viewModelScope.launch { repository.clearScanHistory() }
    }

    fun formatSize(bytes: Long) = repository.formatSize(bytes)
}

@AndroidEntryPoint
class ScanHistoryFragment : Fragment() {

    private var _binding: FragmentScanHistoryBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ScanHistoryViewModel by viewModels()
    private lateinit var adapter: ScanHistoryAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentScanHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = ScanHistoryAdapter { viewModel.formatSize(it) }

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@ScanHistoryFragment.adapter
        }

        binding.btnClearHistory.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Clear scan history?")
                .setMessage("This removes the history log only. Your archive and current duplicate results are not affected.")
                .setPositiveButton("Clear") { _, _ -> viewModel.clearHistory() }
                .setNegativeButton("Cancel", null).show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.sessions.collect { sessions ->
                binding.tvEmpty.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
                binding.recyclerView.visibility = if (sessions.isEmpty()) View.GONE else View.VISIBLE
                binding.btnClearHistory.visibility = if (sessions.isEmpty()) View.GONE else View.VISIBLE
                adapter.submitList(sessions)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.totalReclaimed.collect { bytes ->
                binding.tvTotalReclaimed.text = "💾 Total reclaimed: ${viewModel.formatSize(bytes)}"
            }
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}

class ScanHistoryAdapter(
    private val formatSize: (Long) -> String
) : RecyclerView.Adapter<ScanHistoryAdapter.VH>() {

    private var items: List<ScanSession> = emptyList()

    fun submitList(list: List<ScanSession>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_scan_session, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val tvDate: TextView = v.findViewById(R.id.tvDate)
        private val tvType: TextView = v.findViewById(R.id.tvScanType)
        private val tvStats: TextView = v.findViewById(R.id.tvStats)
        private val tvRecoverable: TextView = v.findViewById(R.id.tvRecoverable)
        private val tvReclaimed: TextView = v.findViewById(R.id.tvReclaimed)

        fun bind(session: ScanSession) {
            val df = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
            tvDate.text = df.format(Date(session.startedAt))
            tvType.text = when (session.scanType) {
                "QUICK" -> "⚡ Quick Scan"
                "INCREMENTAL" -> "🔁 Incremental Scan"
                else -> "🔍 Full Scan"
            }
            val durationSec = session.durationMs / 1000.0
            tvStats.text = "${session.filesScanned} files scanned · ${session.duplicateGroupsFound} group(s) found · %.1fs".format(durationSec)
            tvRecoverable.text = "Recoverable: ${formatSize(session.recoverableBytes)}"
            tvReclaimed.text = if (session.actualReclaimedBytes > 0)
                "✅ Reclaimed: ${formatSize(session.actualReclaimedBytes)}"
            else "Reclaimed: —"
        }
    }
}
