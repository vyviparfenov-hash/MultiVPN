package com.amneziaclient.simple.ui.apps

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.amneziaclient.simple.R
import com.amneziaclient.simple.databinding.FragmentAppsBinding
import com.amneziaclient.simple.ui.showTopSnackbar
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class AppsFragment : Fragment() {

    private var _binding: FragmentAppsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AppSelectionViewModel by viewModels()
    private lateinit var adapter: AppListAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAppsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = AppListAdapter { packageName -> viewModel.onToggle(packageName) }
        binding.recyclerApps.layoutManager = GridLayoutManager(requireContext(), 4)
        binding.recyclerApps.adapter = adapter

        binding.searchInput.doAfterTextChanged { viewModel.onQueryChanged(it?.toString().orEmpty()) }
        // Явно просим показать клавиатуру по тапу/фокусу — подстраховка на
        // случай, если система сама её не показывает (замечен такой сбой
        // после манипуляций с системными диалогами IKEv2).
        binding.searchInput.setOnClickListener { showKeyboardFor(binding.searchInput) }
        binding.searchInput.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) showKeyboardFor(view)
        }
        binding.buttonSelectAll.setOnClickListener { viewModel.selectAll() }
        binding.buttonDeselectAll.setOnClickListener { viewModel.deselectAll() }
        binding.buttonApply.setOnClickListener {
            viewModel.apply()
            binding.searchInput.text?.clear()
            viewModel.clearQuery()
            showSavedSnackbarAtTop()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    adapter.submitList(state.visibleRows)
                    binding.progressLoading.isVisible = state.isLoading
                    val protocolName = state.splitTunnelUnsupportedProtocolName
                    if (protocolName != null) {
                        binding.textSplitTunnelUnsupportedWarning.text =
                            getString(R.string.split_tunnel_unsupported_warning, protocolName)
                        binding.textSplitTunnelUnsupportedWarning.visibility = View.VISIBLE
                    } else {
                        binding.textSplitTunnelUnsupportedWarning.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun showSavedSnackbarAtTop() {
        binding.root.showTopSnackbar(R.string.apps_selection_saved, Snackbar.LENGTH_SHORT)
    }

    private fun showKeyboardFor(view: View) {
        view.requestFocus()
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
