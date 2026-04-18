package com.ikeilo.skyr

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.Charset

object MusicParser {
    private val keyPattern = Regex("^(?:\\d)?Key(\\d+)$")

    fun decode(bytes: ByteArray): String {
        return when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                String(bytes, 2, bytes.size - 2, Charset.forName("UTF-16LE"))
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                String(bytes, 2, bytes.size - 2, Charset.forName("UTF-16BE"))
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            else -> String(bytes, Charsets.UTF_8)
        }
    }

    fun parse(fileName: String, content: String): Song {
        val root = JSONArray(content)
        val song = root.getJSONObject(0)
        if (song.optBoolean("isEncrypted", false)) {
            throw IllegalArgumentException("乐谱已加密，无法播放")
        }
        val notes = song.getJSONArray("songNotes")
        if (notes.length() == 0 || notes.opt(0) !is JSONObject) {
            throw IllegalArgumentException("乐谱格式不受支持")
        }

        val events = mutableListOf<MusicEvent>()
        val first = notes.getJSONObject(0)
        val firstTime = first.optLong("time", 0L)
        if (firstTime > 0L) {
            events += MusicEvent(delayMs = firstTime)
        }
        events += MusicEvent(keys = listOf(parseKey(first)))

        for (i in 1 until notes.length()) {
            val current = notes.getJSONObject(i)
            val previous = notes.getJSONObject(i - 1)
            val currentTime = current.optLong("time", 0L)
            val previousTime = previous.optLong("time", 0L)
            val key = parseKey(current)
            if (currentTime == previousTime && events.isNotEmpty() && events.last().keys.isNotEmpty()) {
                val last = events.removeAt(events.lastIndex)
                events += last.copy(keys = last.keys + key)
            } else {
                val delay = (currentTime - previousTime).coerceAtLeast(0L)
                if (delay > 0L) {
                    events += MusicEvent(delayMs = delay)
                }
                events += MusicEvent(keys = listOf(key))
            }
        }

        return Song(
            name = song.optString("name").ifBlank { fileName.removeSuffix(".txt") },
            events = events
        )
    }

    private fun parseKey(note: JSONObject): Int {
        val raw = note.optString("key")
        val match = keyPattern.matchEntire(raw)
            ?: throw IllegalArgumentException("无法识别按键: $raw")
        return match.groupValues[1].toInt()
    }
}
