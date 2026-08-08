package com.amneziaclient.simple.ui.apps

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.amneziaclient.simple.databinding.ItemAppBinding

class AppListAdapter(
    private val onToggle: (String) -> Unit
) : ListAdapter<AppRow, AppListAdapter.AppViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<AppRow>() {
            override fun areItemsTheSame(old: AppRow, new: AppRow) =
                old.info.packageName == new.info.packageName
            override fun areContentsTheSame(old: AppRow, new: AppRow) = old == new
        }
    }

    inner class AppViewHolder(val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return AppViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val row = getItem(position)
        holder.binding.appIcon.setImageDrawable(row.info.icon)
        holder.binding.appLabel.text = row.info.label
        holder.binding.appCheckBadge.visibility = if (row.checked) android.view.View.VISIBLE else android.view.View.GONE
        holder.binding.root.setOnClickListener { onToggle(row.info.packageName) }
    }
}
