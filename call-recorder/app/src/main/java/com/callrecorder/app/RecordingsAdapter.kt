package com.callrecorder.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class RecordingsAdapter(
    private var items: List<RecordingItem>,
    private val onPlay: (Recording) -> Unit,
    private val onShare: (Recording) -> Unit,
    private val onDelete: (Recording) -> Unit,
) : RecyclerView.Adapter<RecordingsAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.title)
        val subtitle: TextView = view.findViewById(R.id.subtitle)
        val play: ImageButton = view.findViewById(R.id.play)
        val share: ImageButton = view.findViewById(R.id.share)
        val delete: ImageButton = view.findViewById(R.id.delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_recording, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        holder.subtitle.text = item.subtitle
        holder.play.setOnClickListener { onPlay(item.recording) }
        holder.share.setOnClickListener { onShare(item.recording) }
        holder.delete.setOnClickListener { onDelete(item.recording) }
    }

    override fun getItemCount(): Int = items.size

    fun submit(newItems: List<RecordingItem>) {
        items = newItems
        notifyDataSetChanged()
    }
}
