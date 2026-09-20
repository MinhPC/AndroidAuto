package com.minhphan.launcher.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Persists the ordered list of dock app keys. `null` means the user has never had a dock yet. */
class FavoritesStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    private val _keys = MutableStateFlow(read())
    val keys: StateFlow<List<String>?> = _keys

    fun set(keys: List<String>) {
        prefs.edit().putString(KEY_FAVORITES, keys.joinToString("\n")).apply()
        _keys.value = keys
    }

    private fun read(): List<String>? =
        prefs.getString(KEY_FAVORITES, null)?.split('\n')?.filter { it.isNotEmpty() }

    private companion object {
        const val KEY_FAVORITES = "favorites"
    }
}
