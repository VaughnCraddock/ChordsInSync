package com.music.bitchord.data.sync

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The sync engine's durable state: the pairs, and each pair's index of what
 * both sides last agreed on.
 *
 * JSON in SharedPreferences, the house pattern the download records already
 * use — there is no database in this app and a sync index does not justify
 * becoming the first. The pairs list is small enough to rewrite whole; an
 * index can hold thousands of files, so each pair's index is its own key,
 * rewritten only when that pair runs.
 */
object SyncStore {

    private const val PREFS = "bitchord_sync"
    private const val KEY_PAIRS = "sync_pairs"
    private const val KEY_INDEX_PREFIX = "sync_index_"

    private lateinit var prefs: SharedPreferences
    private val json = Json { ignoreUnknownKeys = true }

    private val pairsSerializer = ListSerializer(SyncPair.serializer())
    private val indexSerializer = MapSerializer(String.serializer(), SyncIndexEntry.serializer())

    private val _pairs = MutableStateFlow<List<SyncPair>>(emptyList())
    val pairs: StateFlow<List<SyncPair>> = _pairs.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _pairs.value = runCatching {
            json.decodeFromString(pairsSerializer, prefs.getString(KEY_PAIRS, null) ?: "[]")
        }.getOrDefault(emptyList())
    }

    fun pair(id: String): SyncPair? = _pairs.value.firstOrNull { it.id == id }

    fun addPair(pair: SyncPair) {
        write(_pairs.value + pair)
    }

    fun updatePair(pair: SyncPair) {
        write(_pairs.value.map { if (it.id == pair.id) pair else it })
    }

    /** Removes the pair and its index. The folders and files on both sides are untouched. */
    fun removePair(id: String) {
        write(_pairs.value.filterNot { it.id == id })
        prefs.edit().remove(KEY_INDEX_PREFIX + id).apply()
    }

    /** Records the outcome of a finished run against its pair. */
    fun setStatus(id: String, status: SyncRunStatus) {
        val current = pair(id) ?: return
        updatePair(current.copy(status = status))
    }

    fun loadIndex(pairId: String): MutableMap<String, SyncIndexEntry> = runCatching {
        json.decodeFromString(indexSerializer, prefs.getString(KEY_INDEX_PREFIX + pairId, null) ?: "{}")
    }.getOrDefault(emptyMap()).toMutableMap()

    fun saveIndex(pairId: String, index: Map<String, SyncIndexEntry>) {
        prefs.edit()
            .putString(KEY_INDEX_PREFIX + pairId, json.encodeToString(indexSerializer, index))
            .apply()
    }

    private fun write(pairs: List<SyncPair>) {
        _pairs.value = pairs
        prefs.edit().putString(KEY_PAIRS, json.encodeToString(pairsSerializer, pairs)).apply()
    }
}
