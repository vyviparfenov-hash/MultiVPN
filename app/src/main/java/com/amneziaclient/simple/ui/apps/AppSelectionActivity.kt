package com.amneziaclient.simple.ui.apps

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.amneziaclient.simple.databinding.ActivityAppSelectionBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class AppSelectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppSelectionBinding
    private val viewModel: AppSelectionViewModel by viewModels()
    private lateinit var adapter: AppListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = AppListAdapter { packageName -> viewModel.onToggle(packageName) }
        binding.recyclerApps.layoutManager = LinearLayoutManager(this)
        binding.recyclerApps.adapter = adapter

        binding.searchInput.doAfterTextChanged { viewModel.onQueryChanged(it?.toString().orEmpty()) }
        binding.buttonSelectAll.setOnClickListener { viewModel.selectAll() }
        binding.buttonDeselectAll.setOnClickListener { viewModel.deselectAll() }
        binding.buttonApply.setOnClickListener {
            viewModel.apply()
            finish()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    adapter.submitList(state.visibleRows)
                    binding.progressLoading.isVisible = state.isLoading
                }
            }
        }
    }
}
