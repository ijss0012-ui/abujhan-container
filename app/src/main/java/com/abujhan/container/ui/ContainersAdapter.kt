package com.abujhan.container.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.abujhan.container.data.ContainerRecord
import com.abujhan.container.databinding.ItemContainerBinding

class ContainersAdapter(
    private val onLaunch: (ContainerRecord) -> Unit,
    private val onDelete: (ContainerRecord) -> Unit,
    private val onSetLocation: (ContainerRecord) -> Unit,
) : RecyclerView.Adapter<ContainersAdapter.ViewHolder>() {

    private val items = ArrayList<ContainerRecord>()

    fun submit(list: List<ContainerRecord>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemContainerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])

    inner class ViewHolder(private val binding: ItemContainerBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ContainerRecord) {
            binding.containerLabel.text = item.label
            binding.containerPackage.text = item.containerPackage
            binding.containerLocation.text = if (item.hasLocation) {
                "الموقع: ${item.latitude}, ${item.longitude}"
            } else {
                "لا يوجد موقع محدّد"
            }
            binding.containerIcon.setImageResource(android.R.drawable.ic_menu_mylocation)
            binding.btnContainerLaunch.setOnClickListener { onLaunch(item) }
            binding.btnContainerDelete.setOnClickListener { onDelete(item) }
            binding.btnContainerLocation.setOnClickListener { onSetLocation(item) }
        }
    }
}
