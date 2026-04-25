package com.ikeilo.skyr

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class SongLibrary(private val context: Context) {
    private val prefs = context.getSharedPreferences("song_library", Context.MODE_PRIVATE)

    fun songs(mode: SongListMode): List<SongRef> {
        val all = allSongs()
        return when (mode) {
            SongListMode.PLAYLIST -> {
                val favorites = favoriteIds()
                all.filter { it.id in favorites }
            }
            SongListMode.ALL -> all
        }
    }

    fun allSongs(): List<SongRef> {
        val assets = assetSongs()
        val documents = importedSongs()
        return assets + documents
    }

    fun registerImportedSong(uri: Uri, songName: String): SongRef {
        val normalizedName = songName.ifBlank { uri.lastPathSegment?.substringAfterLast('/') ?: "Imported" }
        val record = SongRef(
            id = "doc:${uri}",
            name = normalizedName,
            source = SongSource.DOCUMENT,
            uriString = uri.toString()
        )
        val items = importedSongs().toMutableList()
        val index = items.indexOfFirst { it.id == record.id }
        if (index >= 0) {
            items[index] = record
        } else {
            items += record
        }
        saveImportedSongs(items)
        return record
    }

    fun loadSong(ref: SongRef): Song {
        val bytes = when (ref.source) {
            SongSource.ASSET -> {
                val assetName = ref.assetName ?: throw IllegalArgumentException("缺少内置乐谱路径")
                context.assets.open("music/$assetName").use { it.readBytes() }
            }
            SongSource.DOCUMENT -> {
                val uriString = ref.uriString ?: throw IllegalArgumentException("缺少导入乐谱路径")
                context.contentResolver.openInputStream(Uri.parse(uriString))?.use { it.readBytes() }
                    ?: throw IllegalArgumentException("无法读取导入乐谱")
            }
        }
        return MusicParser.parse(ref.name, MusicParser.decode(bytes))
    }

    fun isFavorite(ref: SongRef?): Boolean {
        if (ref == null) return false
        return ref.id in favoriteIds()
    }

    fun toggleFavorite(ref: SongRef): Boolean {
        val updated = favoriteIds().toMutableSet()
        val nowFavorite = if (updated.add(ref.id)) {
            true
        } else {
            updated.remove(ref.id)
            false
        }
        prefs.edit().putStringSet(KEY_FAVORITES, updated).apply()
        return nowFavorite
    }

    fun favoriteCount(): Int = favoriteIds().size

    fun defaultMode(): SongListMode {
        return if (favoriteCount() > 0) SongListMode.PLAYLIST else SongListMode.ALL
    }

    fun findById(id: String?): SongRef? {
        if (id.isNullOrBlank()) return null
        return allSongs().firstOrNull { it.id == id }
    }

    private fun assetSongs(): List<SongRef> {
        return context.assets.list("music")
            ?.filter { it.endsWith(".txt", ignoreCase = true) }
            ?.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.removeSuffix(".txt") })
            ?.map { assetName ->
                SongRef(
                    id = "asset:$assetName",
                    name = assetName.removeSuffix(".txt"),
                    source = SongSource.ASSET,
                    assetName = assetName
                )
            }
            .orEmpty()
    }

    private fun importedSongs(): List<SongRef> {
        val raw = prefs.getString(KEY_IMPORTED_SONGS, null) ?: return emptyList()
        val array = JSONArray(raw)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id")
                val name = item.optString("name")
                val uriString = item.optString("uri")
                if (id.isBlank() || name.isBlank() || uriString.isBlank()) continue
                add(
                    SongRef(
                        id = id,
                        name = name,
                        source = SongSource.DOCUMENT,
                        uriString = uriString
                    )
                )
            }
        }.sortedBy { it.name.lowercase(Locale.getDefault()) }
    }

    private fun saveImportedSongs(items: List<SongRef>) {
        val array = JSONArray()
        items.sortedBy { it.name.lowercase(Locale.getDefault()) }.forEach { ref ->
            array.put(
                JSONObject().apply {
                    put("id", ref.id)
                    put("name", ref.name)
                    put("uri", ref.uriString)
                }
            )
        }
        prefs.edit().putString(KEY_IMPORTED_SONGS, array.toString()).apply()
    }

    private fun favoriteIds(): Set<String> {
        return prefs.getStringSet(KEY_FAVORITES, emptySet()).orEmpty()
    }

    private companion object {
        const val KEY_IMPORTED_SONGS = "imported_songs"
        const val KEY_FAVORITES = "favorites"
    }
}
