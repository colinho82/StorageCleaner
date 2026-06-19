package com.storagecleaner.ui.folders

import android.os.Bundle
import android.view.*
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.storagecleaner.R
import com.storagecleaner.data.model.*
import com.storagecleaner.data.repository.StorageRepository
import com.storagecleaner.databinding.FragmentFolderPickerBinding
import com.storagecleaner.util.FolderBrowser
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

// ════════════════════════════════════════════════════════════════════════════
//  SEALED STATE
// ════════════════════════════════════════════════════════════════════════════
sealed class ScanActionState {
    object Idle : ScanActionState()
    data class Processing(val message: String) : ScanActionState()
    data class MoveDone(val count: Int, val folderName: String, val failed: Int) : ScanActionState()
}

// ════════════════════════════════════════════════════════════════════════════
//  VIEW MODEL
// ════════════════════════════════════════════════════════════════════════════
@HiltViewModel
class FolderPickerViewModel @Inject constructor(
    private val browser: FolderBrowser,
    private val repository: StorageRepository
) : ViewModel() {

    // --- Folder state ---
    private val _photoFolders = MutableStateFlow<List<PhoneFolder>>(emptyList())
    val photoFolders: StateFlow<List<PhoneFolder>> = _photoFolders

    private val _docFolders = MutableStateFlow<List<PhoneFolder>>(emptyList())
    val docFolders: StateFlow<List<PhoneFolder>> = _docFolders

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    val scanProgress = repository.scanProgress

    /** Rock-solid scan completion event — SharedFlow with replay=1, never missed */
    val scanCompletedEvent = repository.scanCompletedEvent

    val selectedCount: StateFlow<Int> = combine(_photoFolders, _docFolders) { p, d ->
        p.count { it.isSelected } + d.count { it.isSelected }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), 0)

    // --- Scan results state ---
    private val _groups = MutableStateFlow<List<DuplicateGroup>>(emptyList())
    val groups: StateFlow<List<DuplicateGroup>> = _groups

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected

    private val _filter = MutableStateFlow(DupFilter.ALL)
    val filter: StateFlow<DupFilter> = _filter

    private val _sort = MutableStateFlow(DupSort.SAVINGS)
    val sort: StateFlow<DupSort> = _sort

    private val _actionState = MutableStateFlow<ScanActionState>(ScanActionState.Idle)
    val actionState: StateFlow<ScanActionState> = _actionState

    private val _lastScanTime = MutableStateFlow<Long?>(null)
    val lastScanTime: StateFlow<Long?> = _lastScanTime

    private val _scanDurationMs = MutableStateFlow(0L)
    val scanDurationMs: StateFlow<Long> = _scanDurationMs

    private val _hasResults = MutableStateFlow(false)
    val hasResults: StateFlow<Boolean> = _hasResults

    val protectedUris: StateFlow<Set<String>> = repository.getProtectedFiles()
        .map { list -> list.map { it.uri }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptySet())

    val filteredGroups: StateFlow<List<DuplicateGroup>> = combine(_groups, _filter, _sort) { groups, filter, sort ->
        val filtered = when (filter) {
            DupFilter.ALL       -> groups
            DupFilter.PHOTOS    -> groups.filter { it.fileType == FileType.IMAGE }
            DupFilter.VIDEOS    -> groups.filter { it.fileType == FileType.VIDEO }
            DupFilter.DOCUMENTS -> groups.filter { it.fileType == FileType.DOCUMENT }
            DupFilter.SAFE      -> groups.filter { it.confidenceLevel == ConfidenceLevel.SAFE }
            DupFilter.REVIEW    -> groups.filter { it.confidenceLevel != ConfidenceLevel.SAFE }
        }
        when (sort) {
            DupSort.SAVINGS    -> filtered.sortedByDescending { it.totalWastedBytes }
            DupSort.CONFIDENCE -> filtered.sortedByDescending { it.confidenceScore }
            DupSort.NEWEST     -> filtered.sortedByDescending { g -> g.files.maxOf { it.dateModified } }
            DupSort.OLDEST     -> filtered.sortedBy { g -> g.files.minOf { it.dateModified } }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    val totalWasted: StateFlow<Long> = _groups.map { g -> g.sumOf { it.totalWastedBytes } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), 0L)

    val selectedCount2: StateFlow<Int> = _selected.map { it.size }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    // --- Folder actions ---
    fun load() {
        viewModelScope.launch {
            _loading.value = true
            val (photos, docs) = browser.getAllFolders()
            _photoFolders.value = photos
            _docFolders.value = docs
            _loading.value = false
            // Load any cached results
            val cached = repository.getCachedDuplicates()
            if (cached.isNotEmpty()) {
                _groups.value = cached
                _lastScanTime.value = repository.lastScanTime()
                _scanDurationMs.value = repository.getLatestScanSession()?.durationMs ?: 0L
                _hasResults.value = true
            }
        }
    }

    fun togglePhoto(path: String) {
        _photoFolders.value = _photoFolders.value.map { if (it.path == path) it.copy(isSelected = !it.isSelected) else it }
    }
    fun toggleDoc(path: String) {
        _docFolders.value = _docFolders.value.map { if (it.path == path) it.copy(isSelected = !it.isSelected) else it }
    }
    fun selectAllPhotos(select: Boolean) { _photoFolders.value = _photoFolders.value.map { it.copy(isSelected = select) } }
    fun selectAllDocs(select: Boolean)   { _docFolders.value   = _docFolders.value.map   { it.copy(isSelected = select) } }

    fun startScan(mode: ScanMode = ScanMode.FULL) {
        val selected = (_photoFolders.value + _docFolders.value).filter { it.isSelected }
        val scope = ScanScope(
            folders = selected,
            includeImages = _photoFolders.value.any { it.isSelected },
            includeDocuments = _docFolders.value.any { it.isSelected },
            mode = mode
        )
        viewModelScope.launch { repository.startScanWithScope(scope) }
    }

    fun onScanCompleted() {
        viewModelScope.launch {
            val results = repository.getCachedDuplicates()
            val time = repository.lastScanTime()
            val duration = repository.getLatestScanSession()?.durationMs ?: 0L
            _groups.value = results
            _lastScanTime.value = time
            _scanDurationMs.value = duration
            kotlinx.coroutines.delay(50)
            _hasResults.value = true
        }
    }

    /** Loads results from cache and updates internal state. Returns the groups directly
     *  so the Fragment can update the UI immediately without waiting for Flow emissions. */
    suspend fun loadAndGetResults(): List<DuplicateGroup> {
        val results = repository.getCachedDuplicates()
        val time = repository.lastScanTime()
        val duration = repository.getLatestScanSession()?.durationMs ?: 0L
        _groups.value = results
        _lastScanTime.value = time
        _scanDurationMs.value = duration
        _hasResults.value = true
        return results
    }

    fun rescan() {
        viewModelScope.launch {
            repository.clearCachedDuplicates()
            _groups.value = emptyList()
            _selected.value = emptySet()
            _hasResults.value = false
            _lastScanTime.value = null
            _scanDurationMs.value = 0L
            _actionState.value = ScanActionState.Idle
        }
    }

    // --- Results actions ---
    fun setFilter(f: DupFilter) { _filter.value = f }
    fun setSort(s: DupSort)     { _sort.value = s }

    fun toggleSelect(uri: String) {
        val cur = _selected.value.toMutableSet()
        if (cur.contains(uri)) cur.remove(uri) else cur.add(uri)
        _selected.value = cur
    }
    fun isSelected(uri: String) = _selected.value.contains(uri)
    fun clearSelection() { _selected.value = emptySet() }

    fun selectAllCopies() {
        val protected = protectedUris.value
        _selected.value = filteredGroups.value.flatMap { group ->
            group.files.filterIndexed { i, f ->
                i != group.recommendedKeepIndex && f.uri.toString() !in protected
            }.map { it.uri.toString() }
        }.toSet()
    }

    fun smartSelect() = selectAllCopies()

    fun toggleProtect(file: ScannedFile) {
        viewModelScope.launch {
            val uri = file.uri.toString()
            if (protectedUris.value.contains(uri)) repository.unprotectFile(uri)
            else {
                repository.protectFile(file)
                val cur = _selected.value.toMutableSet(); cur.remove(uri); _selected.value = cur
            }
        }
    }

    data class SimulatorData(
        val fileCount: Int, val totalBytes: Long,
        val affectedFolders: List<String>, val archiveDestination: String
    )

    fun buildSimulatorData(): SimulatorData {
        val allFiles = _groups.value.flatMap { it.files }
        val toMove = allFiles.filter { _selected.value.contains(it.uri.toString()) }
        val folders = toMove.map { java.io.File(it.path).parent ?: it.path }
            .map { java.io.File(it).name }.distinct()
        return SimulatorData(
            fileCount = toMove.size, totalBytes = toMove.sumOf { it.size },
            affectedFolders = folders,
            archiveDestination = "Documents/StorageCleaner/${repository.generateArchiveFolderName()}"
        )
    }

    fun moveSelectedToArchive() {
        val allFiles = _groups.value.flatMap { it.files }
        val toMove = allFiles.filter { _selected.value.contains(it.uri.toString()) }
        if (toMove.isEmpty()) return

        viewModelScope.launch {
            _actionState.value = ScanActionState.Processing("Moving ${toMove.size} files…")
            val result = repository.moveToArchive(toMove) { done, total ->
                _actionState.value = ScanActionState.Processing("Verifying & moving $done / $total…")
            }
            val movedUris = result.succeeded.map { it.first.uri.toString() }.toSet()
            val updatedGroups = _groups.value.mapNotNull { group ->
                val remaining = group.files.filter { it.uri.toString() !in movedUris }
                if (remaining.size < 2) { viewModelScope.launch { repository.removeCachedGroup(group.groupId) }; null }
                else group.copy(files = remaining)
            }
            repository.saveCachedDuplicates(updatedGroups)
            _groups.value = updatedGroups
            _selected.value = emptySet()
            val folderName = result.succeeded.firstOrNull()?.second?.let { java.io.File(it).parentFile?.name } ?: "Archive"
            _actionState.value = ScanActionState.MoveDone(result.succeeded.size, folderName, result.failed.size)
        }
    }

    fun formatSize(bytes: Long) = repository.formatSize(bytes)
    fun formatDate(epoch: Long?): String =
        if (epoch == null) "Never"
        else SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(epoch))
}

enum class DupFilter { ALL, PHOTOS, VIDEOS, DOCUMENTS, SAFE, REVIEW }
enum class DupSort { SAVINGS, CONFIDENCE, NEWEST, OLDEST }

// ════════════════════════════════════════════════════════════════════════════
//  FRAGMENT
// ════════════════════════════════════════════════════════════════════════════
@AndroidEntryPoint
class FolderPickerFragment : Fragment() {

    private var _binding: FragmentFolderPickerBinding? = null
    private val binding get() = _binding!!
    private val viewModel: FolderPickerViewModel by viewModels()
    private lateinit var folderAdapter: FolderAdapter
    private lateinit var resultsAdapter: ScanResultsAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFolderPickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupFolderAccordion()
        setupResultsAccordion()
        setupMenu()
        observeViewModel()
        viewModel.load()
    }

    // ── Accordion 1: Folder Selection ────────────────────────────────────────
    private fun setupFolderAccordion() {
        folderAdapter = FolderAdapter(onToggle = { folder ->
            if (binding.tabLayout.selectedTabPosition == 0) viewModel.togglePhoto(folder.path)
            else viewModel.toggleDoc(folder.path)
        })

        binding.recyclerFolders.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = folderAdapter
            isNestedScrollingEnabled = false
        }

        // Toggle accordion
        binding.headerFolders.setOnClickListener {
            val isVisible = binding.folderContent.visibility == View.VISIBLE
            binding.folderContent.visibility = if (isVisible) View.GONE else View.VISIBLE
            binding.ivFolderChevron.rotation = if (isVisible) 0f else 180f
        }

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) { refreshFolderList() }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        binding.btnSelectAll.setOnClickListener {
            if (binding.tabLayout.selectedTabPosition == 0) viewModel.selectAllPhotos(true)
            else viewModel.selectAllDocs(true)
        }
        binding.btnClearAll.setOnClickListener {
            if (binding.tabLayout.selectedTabPosition == 0) viewModel.selectAllPhotos(false)
            else viewModel.selectAllDocs(false)
        }

        binding.btnStartScan.setOnClickListener {
            if (viewModel.selectedCount.value == 0) {
                binding.tvHint.text = "⚠️ Please select at least one folder"
                return@setOnClickListener
            }
            viewModel.startScan(ScanMode.FULL)
        }

        binding.btnQuickScan.setOnClickListener {
            viewModel.selectAllPhotos(true)
            viewModel.docFolders.value.filter {
                it.folderType == FolderType.DOWNLOADS || it.folderType == FolderType.SCREENSHOTS
            }.forEach { viewModel.toggleDoc(it.path) }
            viewModel.startScan(ScanMode.QUICK)
        }
    }

    // ── Accordion 2: Scan Results ─────────────────────────────────────────────
    private fun setupResultsAccordion() {
        resultsAdapter = ScanResultsAdapter(
            onFileClick     = { file -> findNavController().navigate(FolderPickerFragmentDirections.actionFolderPickerToPreview(file)) },
            onToggle        = { file -> viewModel.toggleSelect(file.uri.toString()) },
            isSelected      = { file -> viewModel.isSelected(file.uri.toString()) },
            isProtected     = { file -> viewModel.protectedUris.value.contains(file.uri.toString()) },
            onProtectToggle = { file -> viewModel.toggleProtect(file) },
            onCompareClick  = { a, b, sim ->
                findNavController().navigate(FolderPickerFragmentDirections.actionFolderPickerToComparison(a, b, sim))
            }
        )

        binding.recyclerResults.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = resultsAdapter
            isNestedScrollingEnabled = false
        }

        // Toggle accordion
        binding.headerResults.setOnClickListener {
            val isVisible = binding.resultsContent.visibility == View.VISIBLE
            binding.resultsContent.visibility = if (isVisible) View.GONE else View.VISIBLE
            binding.ivResultsChevron.rotation = if (isVisible) 0f else 180f
        }

        // Filter chips
        val filterOptions = listOf(
            DupFilter.ALL to "All", DupFilter.PHOTOS to "📷 Photos",
            DupFilter.VIDEOS to "🎬 Videos", DupFilter.DOCUMENTS to "📄 Docs",
            DupFilter.SAFE to "✅ Safe", DupFilter.REVIEW to "⚠️ Review"
        )
        filterOptions.forEachIndexed { i, (filter, label) ->
            val chip = Chip(requireContext()).apply { text = label; isCheckable = true; isChecked = i == 0 }
            chip.setOnClickListener { viewModel.setFilter(filter) }
            binding.chipGroupFilter.addView(chip)
        }

        // Sort chips
        val sortOptions = listOf(
            DupSort.SAVINGS to "💰 Savings", DupSort.CONFIDENCE to "🎯 Confidence",
            DupSort.NEWEST to "🆕 Newest", DupSort.OLDEST to "📅 Oldest"
        )
        sortOptions.forEachIndexed { i, (sort, label) ->
            val chip = Chip(requireContext()).apply { text = label; isCheckable = true; isChecked = i == 0 }
            chip.setOnClickListener { viewModel.setSort(sort) }
            binding.chipGroupSort.addView(chip)
        }

        binding.btnRescan.setOnClickListener { viewModel.rescan() }

        binding.btnMoveToArchive.setOnClickListener {
            showArchiveComingSoonDialog()
        }
    }

    private fun showArchiveComingSoonDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Coming Soon")
            .setMessage("The Archival function will be available in Phase 2. Coming Soon")
            .setPositiveButton("Close", null)
            .show()
    }

    private fun setupMenu() {
        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.scan_results_menu, menu)
            }
            override fun onMenuItemSelected(item: MenuItem) = when (item.itemId) {
                R.id.menu_smart_select    -> { viewModel.smartSelect(); true }
                R.id.menu_select_all      -> { viewModel.selectAllCopies(); true }
                R.id.menu_clear_selection -> { viewModel.clearSelection(); true }
                else -> false
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    private fun showCleanupSimulator() {
        val data = viewModel.buildSimulatorData()
        val v = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_cleanup_simulator, null)
        v.findViewById<TextView>(R.id.tvSimFileCount).text   = "${data.fileCount} file(s)"
        v.findViewById<TextView>(R.id.tvSimRecovery).text    = viewModel.formatSize(data.totalBytes)
        v.findViewById<TextView>(R.id.tvSimFolders).text     =
            if (data.affectedFolders.isEmpty()) "—"
            else data.affectedFolders.take(3).joinToString(", ") +
                if (data.affectedFolders.size > 3) " +${data.affectedFolders.size - 3} more" else ""
        v.findViewById<TextView>(R.id.tvSimDestination).text = data.archiveDestination

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("✂️ Confirm Cleanup").setView(v)
            .setPositiveButton("Move to Archive") { _, _ -> viewModel.moveSelectedToArchive() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.loading.collectLatest { loading ->
                binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            combine(viewModel.photoFolders, viewModel.docFolders) { _, _ -> Unit }.collectLatest { refreshFolderList() }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.selectedCount.collectLatest { count ->
                binding.btnStartScan.text = if (count == 0) "Select folders to scan" else "🔍 Scan $count folder(s)"
                binding.btnStartScan.isEnabled = count > 0
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.scanProgress.collect { progress ->
                when (progress) {
                    is ScanProgress.Scanning -> {
                        binding.scanProgressGroup.visibility = View.VISIBLE
                        binding.tvScanPhase.text = progress.phase.ifEmpty { "Scanning…" }
                        binding.tvScanFile.text = progress.currentFileName
                        binding.scanProgressBar.apply {
                            isIndeterminate = progress.total == 0
                            if (progress.total > 0) { max = progress.total; setProgress(progress.current, true) }
                        }
                    }
                    is ScanProgress.Completed -> {
                        binding.scanProgressGroup.visibility = View.GONE
                    }
                    is ScanProgress.Error -> {
                        binding.scanProgressGroup.visibility = View.GONE
                        binding.tvHint.text = "❌ ${progress.message}"
                    }
                    else -> binding.scanProgressGroup.visibility = View.GONE
                }
            }
        }

        // After scan completes, just reload — reads from cache and updates UI
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.scanCompletedEvent.collect {
                viewModel.load()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.hasResults.collectLatest { hasResults ->
                binding.accordionResults.visibility = if (hasResults) View.VISIBLE else View.GONE
                if (hasResults) {
                    // Auto-expand results, collapse folders
                    binding.resultsContent.visibility = View.VISIBLE
                    binding.ivResultsChevron.rotation = 180f
                    binding.folderContent.visibility = View.GONE
                    binding.ivFolderChevron.rotation = 0f
                    // Explicitly push current groups into adapter
                    val groups = viewModel.filteredGroups.value
                    resultsAdapter.submitList(buildFlatList(groups))
                    binding.tvResultsEmpty.visibility = if (groups.isEmpty()) View.VISIBLE else View.GONE
                    binding.recyclerResults.scrollToPosition(0)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            combine(viewModel.filteredGroups, viewModel.protectedUris) { groups, _ -> groups }
                .collectLatest { groups ->
                    resultsAdapter.submitList(buildFlatList(groups))
                    binding.tvResultsEmpty.visibility = if (groups.isEmpty() && viewModel.hasResults.value) View.VISIBLE else View.GONE
                }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            combine(viewModel.totalWasted, viewModel.lastScanTime, viewModel.scanDurationMs) { wasted, time, duration ->
                Triple(wasted, time, duration)
            }.collectLatest { (wasted, time, duration) ->
                binding.tvTotalWasted.text = "💾 ${viewModel.formatSize(wasted)} reclaimable"
                val durSec = duration / 1000.0
                binding.tvLastScan.text = "Last scan: ${viewModel.formatDate(time)} · ${"%.1f".format(durSec)}s"
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.groups.collectLatest { groups ->
                val totalFiles = groups.sumOf { it.files.size }
                binding.tvGroupCount.text = "${groups.size} group(s) · $totalFiles files"
                binding.headerResultsTitle.text = "📊 Scan Results (${groups.size} groups)"
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.selectedCount2.collectLatest { count ->
                binding.btnMoveToArchive.text = "✂️ Move to Archive ($count)"
                binding.btnMoveToArchive.isEnabled = count > 0
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.selected.collectLatest { resultsAdapter.notifyDataSetChanged() }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.actionState.collectLatest { state ->
                when (state) {
                    is ScanActionState.Idle -> binding.progressOverlay.visibility = View.GONE
                    is ScanActionState.Processing -> {
                        binding.progressOverlay.visibility = View.VISIBLE
                        binding.tvProgressMsg.text = state.message
                    }
                    is ScanActionState.MoveDone -> {
                        binding.progressOverlay.visibility = View.GONE
                        val msg = if (state.failed > 0)
                            "✅ Moved ${state.count} file(s) · ⚠️ ${state.failed} failed (check All Files Access in Settings)"
                        else "✅ Moved ${state.count} file(s) to '${state.folderName}'"
                        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG)
                            .setAction("View Archive") { findNavController().navigate(R.id.action_folderPicker_to_archive) }
                            .show()
                    }
                }
            }
        }
    }

    private fun showResults(groups: List<DuplicateGroup>) {
        // Show and expand results accordion
        binding.accordionResults.visibility = View.VISIBLE
        binding.resultsContent.visibility = View.VISIBLE
        binding.ivResultsChevron.rotation = 180f
        // Collapse folders accordion
        binding.folderContent.visibility = View.GONE
        binding.ivFolderChevron.rotation = 0f
        // Push data directly into adapter — no async, no flows
        val flatList = buildFlatList(groups)
        resultsAdapter.submitList(flatList)
        binding.tvGroupCount.text = "${groups.size} group(s) · ${groups.sumOf { it.files.size }} files"
        binding.tvTotalWasted.text = "💾 ${viewModel.formatSize(groups.sumOf { it.totalWastedBytes })} reclaimable"
        binding.tvResultsEmpty.visibility = if (groups.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerResults.scrollToPosition(0)
    }

    private fun refreshFolderList() {
        val list = if (binding.tabLayout.selectedTabPosition == 0)
            viewModel.photoFolders.value else viewModel.docFolders.value
        folderAdapter.submitList(list)
        binding.tvEmpty.visibility = if (list.isEmpty() && !viewModel.loading.value) View.VISIBLE else View.GONE
    }

    private fun buildFlatList(groups: List<DuplicateGroup>): List<ScanResultItem> {
        val list = mutableListOf<ScanResultItem>()
        groups.forEach { group ->
            list += ScanResultItem.GroupHeader(group)
            group.files.forEachIndexed { i, file ->
                list += ScanResultItem.FileItem(file, i == group.recommendedKeepIndex, group)
            }
        }
        return list
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}

// ════════════════════════════════════════════════════════════════════════════
//  FOLDER ADAPTER
// ════════════════════════════════════════════════════════════════════════════
class FolderAdapter(private val onToggle: (PhoneFolder) -> Unit) :
    ListAdapter<PhoneFolder, FolderAdapter.VH>(Diff()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_folder, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val cbSelect: CheckBox = v.findViewById(R.id.cbSelect)
        private val tvName: TextView   = v.findViewById(R.id.tvFolderName)
        private val tvPath: TextView   = v.findViewById(R.id.tvFolderPath)
        private val tvCount: TextView  = v.findViewById(R.id.tvFileCount)
        private val tvType: TextView   = v.findViewById(R.id.tvFolderType)

        fun bind(folder: PhoneFolder) {
            tvName.text  = folder.name
            tvPath.text  = folder.path
            tvCount.text = "${folder.fileCount} files"
            tvType.text = when (folder.folderType) {
                FolderType.PHOTO_ALBUM     -> "📷 Album"
                FolderType.DOCUMENT_FOLDER -> "📄 Documents"
                FolderType.DOWNLOADS       -> "⬇️ Downloads"
                FolderType.MESSAGING       -> "💬 Messaging"
                FolderType.SCREENSHOTS     -> "🖼️ Screenshots"
                FolderType.OTHER           -> "📁 Folder"
            }
            cbSelect.isChecked = folder.isSelected
            cbSelect.setOnClickListener { onToggle(folder) }
            itemView.setOnClickListener { onToggle(folder) }
        }
    }
    class Diff : DiffUtil.ItemCallback<PhoneFolder>() {
        override fun areItemsTheSame(a: PhoneFolder, b: PhoneFolder) = a.path == b.path
        override fun areContentsTheSame(a: PhoneFolder, b: PhoneFolder) = a == b
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  SCAN RESULTS ADAPTER
// ════════════════════════════════════════════════════════════════════════════
sealed class ScanResultItem {
    data class GroupHeader(val group: DuplicateGroup) : ScanResultItem()
    data class FileItem(val file: ScannedFile, val isRecommendedKeep: Boolean, val group: DuplicateGroup) : ScanResultItem()
}

class ScanResultsAdapter(
    private val onFileClick:     (ScannedFile) -> Unit,
    private val onToggle:        (ScannedFile) -> Unit,
    private val isSelected:      (ScannedFile) -> Boolean,
    private val isProtected:     (ScannedFile) -> Boolean,
    private val onProtectToggle: (ScannedFile) -> Unit,
    private val onCompareClick:  (ScannedFile, ScannedFile, Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<ScanResultItem> = emptyList()
    fun submitList(list: List<ScanResultItem>) { items = list; notifyDataSetChanged() }

    override fun getItemViewType(position: Int) = when (items[position]) {
        is ScanResultItem.GroupHeader -> 0
        is ScanResultItem.FileItem    -> 1
    }
    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 0) HeaderVH(inflater.inflate(R.layout.item_dup_header, parent, false))
        else FileVH(inflater.inflate(R.layout.item_dup_file, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is ScanResultItem.GroupHeader -> (holder as HeaderVH).bind(item.group)
            is ScanResultItem.FileItem    -> (holder as FileVH).bind(item.file, item.isRecommendedKeep, item.group)
        }
    }

    inner class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        private val tvTitle:      TextView = v.findViewById(R.id.tvGroupTitle)
        private val tvBadge:      TextView = v.findViewById(R.id.tvMatchBadge)
        private val tvSim:        TextView = v.findViewById(R.id.tvSimilarity)
        private val tvConfidence: TextView = v.findViewById(R.id.tvConfidence)
        private val tvSaved:      TextView = v.findViewById(R.id.tvSaved)
        private val btnCompare:   TextView = v.findViewById(R.id.btnCompare)

        fun bind(group: DuplicateGroup) {
            tvTitle.text = when (group.fileType) {
                FileType.IMAGE    -> "📷 Similar Photos (${group.files.size} copies)"
                FileType.DOCUMENT -> "📄 Document Match (${group.files.size} copies)"
                FileType.VIDEO    -> "🎬 Duplicate Videos (${group.files.size} copies)"
                else              -> "📁 Duplicates (${group.files.size} copies)"
            }
            tvBadge.text = group.matchLabel
            tvSim.text   = "${group.similarityPct}% similar"
            val (confColor, confLabel) = when (group.confidenceLevel) {
                ConfidenceLevel.SAFE   -> R.color.confidence_safe   to "SAFE ${group.confidenceScore}%"
                ConfidenceLevel.REVIEW -> R.color.confidence_review to "REVIEW ${group.confidenceScore}%"
                ConfidenceLevel.MANUAL -> R.color.confidence_manual to "CHECK ${group.confidenceScore}%"
            }
            tvConfidence.text = confLabel
            tvConfidence.setTextColor(ContextCompat.getColor(itemView.context, confColor))
            tvSaved.text = "Saves ${fmt(group.totalWastedBytes)}"
            if (group.fileType == FileType.IMAGE && group.files.size >= 2) {
                btnCompare.visibility = View.VISIBLE
                btnCompare.setOnClickListener {
                    val keep  = group.files[group.recommendedKeepIndex]
                    val other = group.files.firstOrNull { it != keep } ?: group.files[0]
                    onCompareClick(keep, other, group.similarityPct)
                }
            } else btnCompare.visibility = View.GONE
        }
        private fun fmt(b: Long) = when {
            b >= 1_073_741_824 -> "%.2f GB".format(b / 1_073_741_824.0)
            b >= 1_048_576     -> "%.1f MB".format(b / 1_048_576.0)
            b >= 1_024         -> "%.0f KB".format(b / 1_024.0)
            else -> "$b B"
        }
    }

    inner class FileVH(v: View) : RecyclerView.ViewHolder(v) {
        private val ivThumb:    ImageView = v.findViewById(R.id.ivThumbnail)
        private val tvName:     TextView  = v.findViewById(R.id.tvFileName)
        private val tvDetails:  TextView  = v.findViewById(R.id.tvFileDetails)
        private val tvPath:     TextView  = v.findViewById(R.id.tvFilePath)
        private val tvBadge:    TextView  = v.findViewById(R.id.tvOrigCopyBadge)
        private val cbSelect:   CheckBox  = v.findViewById(R.id.cbSelect)
        private val btnProtect: ImageView = v.findViewById(R.id.btnProtect)

        fun bind(file: ScannedFile, isRecommendedKeep: Boolean, group: DuplicateGroup) {
            tvName.text = file.name
            val df = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
            val resPart = if (file.width > 0 && file.height > 0) " · ${file.width}×${file.height}" else ""
            tvDetails.text = "${fmt(file.size)}$resPart · ${df.format(Date(file.dateModified))}"
            tvPath.text    = file.path.ifEmpty { file.uri.toString() }

            val protected = isProtected(file)
            when {
                protected         -> { tvBadge.text = "PROTECTED"; tvBadge.setBackgroundColor(ContextCompat.getColor(itemView.context, R.color.confidence_manual)) }
                isRecommendedKeep -> { tvBadge.text = "KEEP";      tvBadge.setBackgroundColor(ContextCompat.getColor(itemView.context, R.color.badge_original)) }
                else              -> { tvBadge.text = "COPY";      tvBadge.setBackgroundColor(ContextCompat.getColor(itemView.context, R.color.badge_copy)) }
            }

            if (!isRecommendedKeep && !protected) {
                cbSelect.visibility = View.VISIBLE
                cbSelect.setOnCheckedChangeListener(null)
                cbSelect.isChecked = isSelected(file)
                cbSelect.setOnCheckedChangeListener { _, _ -> onToggle(file) }
            } else {
                cbSelect.visibility = View.GONE
                cbSelect.setOnCheckedChangeListener(null)
            }

            btnProtect.setImageResource(R.drawable.ic_shield)
            btnProtect.alpha = if (protected) 1.0f else 0.35f
            btnProtect.setOnClickListener { onProtectToggle(file) }

            if (file.fileType == FileType.IMAGE || file.fileType == FileType.VIDEO) {
                Glide.with(itemView.context).load(file.uri).centerCrop()
                    .placeholder(R.drawable.ic_image_placeholder).into(ivThumb)
            } else {
                ivThumb.setImageResource(when (file.fileType) {
                    FileType.DOCUMENT -> R.drawable.ic_document
                    FileType.VIDEO    -> R.drawable.ic_video
                    FileType.AUDIO    -> R.drawable.ic_audio
                    else              -> R.drawable.ic_file
                })
            }
            itemView.setOnClickListener { onFileClick(file) }
            itemView.setBackgroundColor(ContextCompat.getColor(itemView.context,
                if (!isRecommendedKeep && !protected && isSelected(file)) R.color.selected_bg else android.R.color.transparent))
        }
        private fun fmt(b: Long) = when {
            b >= 1_073_741_824 -> "%.2f GB".format(b / 1_073_741_824.0)
            b >= 1_048_576     -> "%.1f MB".format(b / 1_048_576.0)
            b >= 1_024         -> "%.0f KB".format(b / 1_024.0)
            else -> "$b B"
        }
    }
}
