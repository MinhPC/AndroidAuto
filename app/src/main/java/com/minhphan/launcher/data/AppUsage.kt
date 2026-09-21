package com.minhphan.launcher.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Once the launch counts add up to more than this they are all halved, so that recent habits count for more. */
private const val HALVE_ABOVE = 300

/**
 * [counts] (launches per app key) with one more launch of [key]. Habits change: when the counts add up to a lot they
 * are all halved, rounding up, so an app that was used a lot last year does not outrank the one used every day now.
 */
fun recordLaunch(counts: Map<String, Int>, key: String): Map<String, Int> {
    val next = counts + (key to (counts[key] ?: 0) + 1)
    return if (next.values.sum() > HALVE_ABOVE) next.mapValues { (_, count) -> (count + 1) / 2 } else next
}

/**
 * The order of the All apps list: the apps on the dock first, in the dock's order; then the apps launched most often
 * here; then the rest. [alphabetical] must already be in name order, which is what apps with the same count keep.
 */
fun <T> smartOrder(alphabetical: List<T>, key: (T) -> String, pinned: List<String>, counts: Map<String, Int>): List<T> {
    val dockPlace = pinned.withIndex().associate { (index, appKey) -> appKey to index }
    // sortedWith is stable: what compares equal stays in name order.
    return alphabetical.sortedWith(
        compareBy<T> { dockPlace[key(it)] ?: Int.MAX_VALUE }.thenByDescending { counts[key(it)] ?: 0 },
    )
}

/** Remembers how many times each app has been launched from the launcher, in the "app_usage" preferences. */
class UsageStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("app_usage", Context.MODE_PRIVATE)
    private val _counts = MutableStateFlow(read())
    val counts: StateFlow<Map<String, Int>> = _counts

    fun record(key: String) {
        val next = recordLaunch(_counts.value, key)
        _counts.value = next
        prefs.edit().putString(KEY_COUNTS, next.entries.joinToString("\n") { "${it.key}\t${it.value}" }).apply()
    }

    // One app a line: its key, a tab, and its count. App keys hold neither tabs nor line breaks.
    private fun read(): Map<String, Int> =
        prefs.getString(KEY_COUNTS, "").orEmpty().lineSequence().mapNotNull { line ->
            val (key, count) = line.split("\t").takeIf { it.size == 2 } ?: return@mapNotNull null
            count.toIntOrNull()?.let { key to it }
        }.toMap()

    private companion object {
        const val KEY_COUNTS = "counts"
    }
}
