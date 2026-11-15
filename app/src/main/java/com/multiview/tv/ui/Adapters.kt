package com.multiview.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.multiview.tv.R
import com.multiview.tv.data.Channel

data class GroupItem(val id: String, val name: String, val count: Int, val locked: Boolean = false)

class GroupAdapter(
    private val onFocus: (GroupItem) -> Unit,
    private val onClick: (GroupItem) -> Unit = onFocus,
) : RecyclerView.Adapter<GroupAdapter.VH>() {

    var items: List<GroupItem> = emptyList()
        set(value) { field = value; notifyDataSetChanged() }
    var selectedId: String? = null
        set(value) { field = value; notifyDataSetChanged() }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.title)
        val count: TextView = v.findViewById(R.id.count)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_group, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val g = items[position]
        h.title.text = if (g.locked) "🔒 ${g.name}" else g.name
        h.count.text = g.count.toString()
        h.itemView.isSelected = g.id == selectedId
        h.itemView.setOnFocusChangeListener { _, has -> if (has) onFocus(g) }
        h.itemView.setOnClickListener { onClick(g) }
    }
}

class ChannelAdapter(
    private val onFocus: (Channel, Int) -> Unit,
    private val onClick: (Channel, Int) -> Unit,
    private val onLongClick: (Channel, Int) -> Unit,
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    var items: List<Channel> = emptyList()
        set(value) { field = value; notifyDataSetChanged() }
    var favorites: Set<String> = emptySet()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val number: TextView = v.findViewById(R.id.number)
        val logo: ImageView = v.findViewById(R.id.logo)
        val name: TextView = v.findViewById(R.id.name)
        val fav: TextView = v.findViewById(R.id.fav)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val c = items[position]
        h.number.text = c.number.toString()
        h.name.text = c.name
        h.fav.visibility = if (favorites.contains(c.id)) View.VISIBLE else View.GONE
        if (c.logo != null) h.logo.load(c.logo) { crossfade(false) } else h.logo.setImageDrawable(null)
        h.itemView.setOnFocusChangeListener { _, has ->
            if (has) h.bindingAdapterPosition.takeIf { it >= 0 }?.let { onFocus(items[it], it) }
        }
        h.itemView.setOnClickListener {
            h.bindingAdapterPosition.takeIf { it >= 0 }?.let { onClick(items[it], it) }
        }
        h.itemView.setOnLongClickListener {
            h.bindingAdapterPosition.takeIf { it >= 0 }?.let { onLongClick(items[it], it) }
            true
        }
    }
}
