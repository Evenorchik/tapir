package com.sleeptalk.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.sleeptalk.app.databinding.ItemSessionBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SessionAdapter(
    private val onPlay: (Session) -> Unit,
    private val onShare: (Session) -> Unit,
    private val onDelete: (Session) -> Unit
) : RecyclerView.Adapter<SessionAdapter.VH>() {

    private val items = mutableListOf<Session>()
    private val dateFmt = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault())

    fun submit(list: List<Session>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemSessionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(private val b: ItemSessionBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(s: Session) {
            b.title.text = dateFmt.format(Date(s.dateMs))
            val ctx = b.root.context
            b.subtitle.text = ctx.getString(
                R.string.session_subtitle,
                formatHms(s.durationMs),
                s.segments,
                formatSize(s.sizeBytes)
            )
            b.play.setOnClickListener { onPlay(s) }
            b.share.setOnClickListener { onShare(s) }
            b.delete.setOnClickListener { onDelete(s) }
        }
    }
}

fun formatHms(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val sec = totalSec % 60
    return String.format(Locale.US, "%02d:%02d:%02d", h, m, sec)
}

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb)
    return String.format(Locale.US, "%.1f MB", kb / 1024.0)
}
