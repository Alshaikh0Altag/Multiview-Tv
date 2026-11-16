package com.multiview.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.multiview.tv.R
import com.multiview.tv.data.Channel

data class GroupItem(val id: String, val name: String, val count: Int, val locked: Boolean = false)

/** Payload used to refresh only the "selected" look without rebinding (keeps D-pad focus). */
private const val PAYLOAD_STATE = "state"

/**
 * Groups / seasons list.
 * Focus-safe: changing [selectedId] only re-binds the two affected rows with a payload,
 * so the row that has focus is never recreated (a full notifyDataSetChanged loses focus on TV).
 */
class GroupAdapter(
    private val onFocus: (GroupItem) -> Unit,
    private val onClick: (GroupItem) -> Unit = onFocus,
    private val onLongClick: ((GroupItem) -> Unit)? = null,
) : RecyclerView.Adapter<GroupAdapter.VH>() {

    init { setHasStableIds(true) }

    var items: List<GroupItem> = emptyList()
        set(value) { field = value; notifyDataSetChanged() }

    var selectedId: String? = null
        set(value) {
            if (field == value) return
            val old = items.indexOfFirst { it.id == field }
            field = value
            val new = items.indexOfFirst { it.id == value }
            if (old >= 0) notifyItemChanged(old, PAYLOAD_STATE)
            if (new >= 0) notifyItemChanged(new, PAYLOAD_STATE)
        }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.title)
        val count: TextView = v.findViewById(R.id.count)
        val marker: View = v.findViewById(R.id.marker)
    }

    override fun getItemId(position: Int) = items[position].id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val h = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_group, parent, false))
        h.itemView.setOnFocusChangeListener { _, has ->
            if (has) h.bindingAdapterPosition.takeIf { it >= 0 }?.let { onFocus(items[it]) }
        }
        h.itemView.setOnClickListener {
            h.bindingAdapterPosition.takeIf { it >= 0 }?.let { onClick(items[it]) }
        }
        onLongClick?.let { cb ->
            h.itemView.setOnLongClickListener {
                h.bindingAdapterPosition.takeIf { it >= 0 }?.let { cb(items[it]) }
                true
            }
        }
        return h
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_STATE)) bindState(h, items[position]) else onBindViewHolder(h, position)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val g = items[position]
        h.title.text = if (g.locked) "🔒  ${g.name}" else g.name
        h.count.text = if (g.count >= 0) g.count.toString() else ""
        bindState(h, g)
    }

    private fun bindState(h: VH, g: GroupItem) {
        val sel = g.id == selectedId
        h.itemView.isSelected = sel
        h.marker.visibility = if (sel) View.VISIBLE else View.INVISIBLE
    }

    fun positionOf(id: String?) = items.indexOfFirst { it.id == id }
}

class ChannelAdapter(
    private val onFocus: (Channel, Int) -> Unit,
    private val onClick: (Channel, Int) -> Unit,
    private val onLongClick: (Channel, Int) -> Unit,
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    init { setHasStableIds(true) }

    var items: List<Channel> = emptyList()
        set(value) { field = value; notifyDataSetChanged() }

    var favorites: Set<String> = emptySet()
        set(value) { field = value; notifyItemRangeChanged(0, items.size, PAYLOAD_STATE) }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val number: TextView = v.findViewById(R.id.number)
        val logo: ImageView = v.findViewById(R.id.logo)
        val name: TextView = v.findViewById(R.id.name)
        val fav: View = v.findViewById(R.id.fav)
    }

    override fun getItemId(position: Int) = items[position].id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val h = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false))
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
        return h
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_STATE)) bindFav(h, items[position]) else onBindViewHolder(h, position)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val c = items[position]
        h.number.text = c.number.toString()
        h.name.text = c.name
        bindFav(h, c)
        if (c.logo != null) {
            h.logo.load(c.logo) {
                crossfade(false)
                error(R.drawable.ic_tv)
                transformations(RoundedCornersTransformation(8f))
            }
        } else {
            h.logo.setImageResource(R.drawable.ic_tv)
        }
    }

    private fun bindFav(h: VH, c: Channel) {
        h.fav.visibility = if (favorites.contains(c.id)) View.VISIBLE else View.GONE
    }
}
