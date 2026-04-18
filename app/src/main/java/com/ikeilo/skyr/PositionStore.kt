package com.ikeilo.skyr

import android.content.Context
import android.graphics.PointF

class PositionStore(context: Context) {
    private val prefs = context.getSharedPreferences("position", Context.MODE_PRIVATE)

    fun load(): PositionConfig? {
        val raw = prefs.getString("points", null) ?: return null
        val points = raw.split(";").mapNotNull { item ->
            val parts = item.split(",")
            if (parts.size != 2) return@mapNotNull null
            PointF(parts[0].toFloatOrNull() ?: return@mapNotNull null, parts[1].toFloatOrNull() ?: return@mapNotNull null)
        }
        return if (points.size == 15) PositionConfig(points) else null
    }

    fun save(config: PositionConfig) {
        val raw = config.points.joinToString(";") { "${it.x},${it.y}" }
        prefs.edit().putString("points", raw).apply()
    }
}
