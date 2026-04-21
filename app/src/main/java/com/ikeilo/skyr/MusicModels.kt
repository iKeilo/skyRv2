package com.ikeilo.skyr

import android.graphics.PointF

data class MusicEvent(
    val delayMs: Long = 0L,
    val keys: List<Int> = emptyList(),
    val durationMs: Long = 0L
)

data class Song(
    val name: String,
    val events: List<MusicEvent>
)

data class PositionConfig(
    val points: List<PointF>
)
