package com.foldermonitor.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FolderAdapter(private val onRemove: (String) -> Unit) : RecyclerView.Adapter<FolderAdapter.VH>() {

    private var rows: List<Pair<String, FolderHistory?>> = emptyList()
    private var rules = Rules()

    fun submit(rows: List<Pair<String, FolderHistory?>>, rules: Rules) {
        this.rows = rows
        this.rules = rules
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val card: MaterialCardView = v.findViewById(R.id.card)
        val name: TextView = v.findViewById(R.id.name)
        val path: TextView = v.findViewById(R.id.path)
        val size: TextView = v.findViewById(R.id.size)
        val growth: TextView = v.findViewById(R.id.growth)
        val status: TextView = v.findViewById(R.id.status)
        val remove: ImageButton = v.findViewById(R.id.remove)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_folder, parent, false))

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(vh: VH, position: Int) {
        val (path, h) = rows[position]
        val ctx = vh.itemView.context
        val now = System.currentTimeMillis()
        vh.name.text = File(path).name.ifEmpty { path }
        vh.path.text = FolderPicker.displayPath(path)
        vh.remove.setOnClickListener { onRemove(path) }

        val alerting = h != null && h.isAlerting(now, rules)
        vh.card.strokeWidth = if (alerting) 4 else 0
        vh.card.strokeColor = ContextCompat.getColor(ctx, R.color.alert)
        vh.card.setCardBackgroundColor(ContextCompat.getColor(ctx, if (alerting) R.color.alert_bg else R.color.surface))

        when {
            h == null || (h.samples.isEmpty() && h.error.isEmpty()) -> {
                vh.size.text = "—"
                vh.growth.text = ""
                vh.status.text = "لم يُفحص بعد"
                vh.status.setTextColor(ContextCompat.getColor(ctx, R.color.text_muted))
            }
            h.error.isNotEmpty() -> {
                vh.size.text = if (h.samples.isEmpty()) "—" else formatSize(h.current)
                vh.growth.text = ""
                vh.status.text = "⚠ ${h.error}"
                vh.status.setTextColor(ContextCompat.getColor(ctx, R.color.alert))
            }
            else -> {
                vh.size.text = formatSize(h.current)
                val (g, base) = h.growth()
                vh.growth.text = when {
                    base == null -> "أول قياس"
                    g > 0 -> "+${formatSize(g)}" +
                        (if (base.size > 0) " (${g * 100 / base.size}%)" else "") +
                        " خلال ${formatSpan(now - base.t)}"
                    else -> "لا زيادة خلال ${formatSpan(now - base.t)}"
                }
                vh.growth.setTextColor(ContextCompat.getColor(ctx, if (alerting) R.color.alert else R.color.text_muted))
                val time = SimpleDateFormat("HH:mm", Locale.US).format(Date(h.lastCheck ?: now))
                if (alerting) {
                    vh.status.text = h.lastAlertText
                    vh.status.setTextColor(ContextCompat.getColor(ctx, R.color.alert))
                } else {
                    vh.status.text = "✓ طبيعي • آخر فحص $time"
                    vh.status.setTextColor(ContextCompat.getColor(ctx, R.color.ok))
                }
            }
        }
    }
}
