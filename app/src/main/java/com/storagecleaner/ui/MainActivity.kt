package com.storagecleaner.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.storagecleaner.R
import com.storagecleaner.databinding.ActivityMainBinding
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHostFragment = supportFragmentManager
            .findFragmentById(binding.navHostFragment.id) as NavHostFragment
        navController = navHostFragment.navController

        binding.bottomNav.setupWithNavController(navController)

        // Archive tab is disabled until Phase 2 — show a "Coming Soon" dialog instead
        binding.bottomNav.setOnItemSelectedListener { item ->
            if (item.itemId == R.id.archiveFragment) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Coming Soon")
                    .setMessage("The Archival function will be available in Phase 2. Coming Soon")
                    .setPositiveButton("Close", null)
                    .show()
                return@setOnItemSelectedListener false
            }
            navController.navigate(item.itemId)
            true
        }

        navController.addOnDestinationChangedListener { _, destination, _ ->
            val tabIds = setOf(
                R.id.homeFragment,
                R.id.folderPickerFragment,
                R.id.archiveFragment,
                R.id.settingsFragment
            )
            if (destination.id in tabIds) {
                binding.bottomNav.menu.findItem(destination.id)?.isChecked = true
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean =
        navController.navigateUp() || super.onSupportNavigateUp()
}
