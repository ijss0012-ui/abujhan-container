package com.abujhan.container.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.abujhan.container.R
import com.abujhan.container.data.AppEntry
import com.abujhan.container.databinding.ItemAppBinding

class AppsAdapter(private val onClone: (AppEntry) -> Unit) :
    RecyclerView.Adapter<AppsAdapter.ViewHolder>() {

    private val items = ArrayList<AppEntry>()
    private val busy = HashSet<String>()

    fun submit(list: List<AppEntry>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun setBusy(packageName: String, value: Boolean) {
        if (value) busy.add(packageName) else busy.remove(packageName)
        val index = items.indexOfFirst { it.packageName == packageName }
        if (index >= 0) notifyItemChanged(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])

    inner class ViewHolder(private val binding: ItemAppBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: AppEntry) {
            binding.appLabel.text = item.label
            binding.appPackage.text = item.packageName
            binding.appIcon.setImageDrawable(item.icon)
            val isBusy = item.packageName in busy
            binding.btnClone.isEnabled = !isBusy
            binding.btnClone.setText(if (isBusy) R.string.cloning_btn else R.string.btn_clone)
            binding.btnClone.setOnClickListener { onClone(item) }
        }
    }
}
