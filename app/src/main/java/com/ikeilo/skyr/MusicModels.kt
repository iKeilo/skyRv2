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

enum class SongSource {
    ASSET,
    DOCUMENT
}

enum class SongListMode {
    PLAYLIST,
    ALL
}

data class SongRef(
    val id: String,
    val name: String,
    val source: SongSource,
    val assetName: String? = null,
    val uriString: String? = null
)

enum class VolumeShortcutTrigger(val storageKey: String, val label: String) {
    SINGLE_UP("single_up", "单击音量+"),
    SINGLE_DOWN("single_down", "单击音量-"),
    DOUBLE_UP("double_up", "双击音量+"),
    DOUBLE_DOWN("double_down", "双击音量-")
}

enum class VolumeShortcutAction(val storageValue: String, val label: String) {
    NONE("none", "无"),
    TOGGLE_UI("toggle_ui", "显示/隐藏UI"),
    PREVIOUS_SONG("previous_song", "上一曲"),
    NEXT_SONG("next_song", "下一曲"),
    START_OR_RESTART("start_or_restart", "开始/重开"),
    PAUSE_OR_RESUME("pause_or_resume", "暂停/继续"),
    STOP("stop", "结束演奏"),
    TOGGLE_SONG_PICKER("toggle_song_picker", "打开/关闭选曲");

    companion object {
        fun fromStorage(value: String?): VolumeShortcutAction {
            return entries.firstOrNull { it.storageValue == value } ?: NONE
        }
    }
}

data class PositionConfig(
    val points: List<PointF>
)
