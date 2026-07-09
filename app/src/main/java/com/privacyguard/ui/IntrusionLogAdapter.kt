package com.privacyguard.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.privacyguard.R
import com.privacyguard.data.IntrusionEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RecyclerView adapter for displaying intrusion events from the Room database.
 * Each item shows app name, timestamp, trigger reason, a confidence gauge,
 * and face count. Tapping an item expands or collapses additional details.
 */
class IntrusionLogAdapter(
    private val entries: MutableList<IntrusionEntity> = mutableListOf()
) : RecyclerView.Adapter<IntrusionLogAdapter.ViewHolder>() {

    /** Track expanded positions for detail toggle. */
    private val expandedPositions = mutableSetOf<Int>()
    private val dateFormat = SimpleDateFormat("MMM dd, yyyy  HH:mm:ss", Locale.getDefault())

    fun submitList(list: List<IntrusionEntity>) {
        entries.clear()
        entries.addAll(list)
        expandedPositions.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_intrusion_entry, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = entries[position]
        holder.bind(entry, position)
    }

    override fun getItemCount(): Int = entries.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val tvAppName: TextView = itemView.findViewById(R.id.tvAppName)
        private val tvTimestamp: TextView = itemView.findViewById(R.id.tvTimestamp)
        private val tvReason: TextView = itemView.findViewById(R.id.tvReason)
        private val tvFaceCount: TextView = itemView.findViewById(R.id.tvFaceCount)
        private val confidenceBar: ProgressBar = itemView.findViewById(R.id.confidenceBar)
        private val tvConfidenceLabel: TextView = itemView.findViewById(R.id.tvConfidenceLabel)
        private val detailsPanel: LinearLayout = itemView.findViewById(R.id.detailsPanel)
        private val tvStrangerSimilarity: TextView = itemView.findViewById(R.id.tvStrangerSimilarity)
        private val tvDismissedStatus: TextView = itemView.findViewById(R.id.tvDismissedStatus)

        fun bind(entry: IntrusionEntity, position: Int) {
            tvAppName.text = entry.appName ?: entry.appPackage ?: "Unknown App"
            tvTimestamp.text = dateFormat.format(Date(entry.timestampMs))
            tvReason.text = formatReason(entry.triggerReason)
            tvFaceCount.text = when (entry.faceCount) {
                0 -> "No faces"
                1 -> "1 face"
                else -> "${entry.faceCount} faces"
            }

            // Confidence gauge
            val clampedConfidence = entry.confidenceScore.coerceIn(0, 100)
            confidenceBar.progress = clampedConfidence
            tvConfidenceLabel.text = "${clampedConfidence}%"
            confidenceBar.progressTintList = android.content.res.ColorStateList.valueOf(
                when {
                    clampedConfidence >= 80 -> Color.parseColor("#FF4444")
                    clampedConfidence >= 50 -> Color.parseColor("#FFAA00")
                    else -> Color.parseColor("#44AA44")
                }
            )

            // Detail panel
            val isExpanded = position in expandedPositions
            detailsPanel.visibility = if (isExpanded) View.VISIBLE else View.GONE

            tvStrangerSimilarity.text = "Stranger similarity: ${"%.1f".format(entry.strangerSimilarity * 100)}%"
            tvDismissedStatus.text = if (entry.dismissed) "Status: Dismissed" else "Status: Active"

            // Tap to toggle
            itemView.setOnClickListener {
                if (isExpanded) {
                    expandedPositions.remove(position)
                } else {
                    expandedPositions.add(position)
                }
                notifyItemChanged(position)
            }
        }

        private fun formatReason(raw: String): String {
            return when (raw) {
                "MULTI_FACE" -> "Multiple faces detected"
                "STRANGER" -> "Stranger detected"
                "NO_FACE_AWAY" -> "User walked away"
                "PEER_OVER_SHOLDER" -> "Shoulder surfing detected"
                "LOW_CONFIDENCE" -> "Low owner confidence"
                else -> raw.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }
            }
        }
    }

    companion object {
        /** Resource layout ID used by [onCreateViewHolder]. */
        val ITEM_LAYOUT = R.layout.item_intrusion_entry
    }
}
