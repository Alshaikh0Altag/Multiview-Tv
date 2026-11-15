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

data class PosterItem(val id: String, val title: String, val image: String?, val sub: String?)

class PosterAdapter(
    private val onClick: (PosterItem) -> Unit,
) : RecyclerView.Adapter<PosterAdapter.VH>() {

    init { setHasStableIds(true) }

    override fun getItemId(position: Int) = items[position].id.hashCode().toLong()

    var items: List<PosterItem> = emptyList()
        set(value) { field = value; notifyDataSetChanged() }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val poster: ImageView = v.findViewById(R.id.poster)
        val title: TextView = v.findViewById(R.id.posterTitle)
        val sub: TextView = v.findViewById(R.id.posterSub)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val h = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_poster, parent, false))
        h.itemView.setOnClickListener {
            h.bindingAdapterPosition.takeIf { it >= 0 }?.let { onClick(items[it]) }
        }
        h.itemView.setOnFocusChangeListener { v, has ->
            val s = if (has) 1.06f else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(120).start()
            v.elevation = if (has) 12f else 0f
        }
        return h
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val p = items[position]
        h.title.text = p.title
        h.sub.text = p.sub?.let { "★ $it" } ?: ""
        if (p.image != null) {
            h.poster.load(p.image) {
                crossfade(false)
                error(R.drawable.ic_movie)
                transformations(RoundedCornersTransformation(16f))
            }
        } else {
            h.poster.setImageResource(R.drawable.ic_movie)
        }
    }
}

data class EpisodeItem(val title: String, val sub: String?)

class EpisodeAdapter(
    private val onClick: (Int) -> Unit,
) : RecyclerView.Adapter<EpisodeAdapter.VH>() {

    var items: List<EpisodeItem> = emptyList()
        set(value) { field = value; notifyDataSetChanged() }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.epTitle)
        val sub: TextView = v.findViewById(R.id.epSub)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_episode, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val e = items[position]
        h.title.text = e.title
        h.sub.text = e.sub ?: ""
        h.sub.visibility = if (e.sub.isNullOrBlank()) View.GONE else View.VISIBLE
        h.itemView.setOnClickListener {
            h.bindingAdapterPosition.takeIf { it >= 0 }?.let(onClick)
        }
    }
}
