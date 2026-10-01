package com.minhphan.launcher.data

/** At most this many apps on the dock, before the two buttons that are always there (all apps and settings). */
const val MAX_DOCK_APPS = 4

/**
 * A button on the dock that opens whatever app the head unit has for the job, so it follows what is installed: its
 * [id] is kept in the dock's list beside the keys of the apps the driver picked ([AppInfo.key]).
 */
enum class DockShortcut(val id: String) {
    Maps("@maps"),
    Music("@music"),
    YouTube("@youtube"),
    ;

    companion object {
        fun of(id: String): DockShortcut? = entries.firstOrNull { it.id == id }
    }
}

/** The dock before the driver changes it: the maps, the music and YouTube. */
val DEFAULT_DOCK_APPS: List<String> = DockShortcut.entries.map { it.id }

/** One button of the dock as shown: a shortcut, or an installed app. */
sealed interface DockEntry {
    val id: String

    data class Shortcut(val shortcut: DockShortcut) : DockEntry {
        override val id get() = shortcut.id
    }

    data class App(val app: AppInfo) : DockEntry {
        override val id get() = app.key
    }
}

/**
 * The dock's buttons for [ids], in their order: an app that is no longer installed is left out (it comes back if it
 * is installed again, until the dock is next changed).
 */
fun resolveDock(ids: List<String>, apps: List<AppInfo>): List<DockEntry> {
    val byKey = apps.associateBy { it.key }
    return ids.mapNotNull { id ->
        DockShortcut.of(id)?.let { DockEntry.Shortcut(it) } ?: byKey[id]?.let { DockEntry.App(it) }
    }
}

/** [ids] with [id] added at the end, or taken out; adding to a full dock, or twice, changes nothing. */
fun withDockApp(ids: List<String>, id: String, on: Boolean): List<String> = when {
    !on -> ids - id
    id in ids || ids.size >= MAX_DOCK_APPS -> ids
    else -> ids + id
}

/** [ids] with [id] moved [by] places (negative: towards the start), as far as the ends allow. */
fun movedDockApp(ids: List<String>, id: String, by: Int): List<String> {
    val from = ids.indexOf(id)
    if (from < 0) return ids
    val to = (from + by).coerceIn(0, ids.lastIndex)
    if (to == from) return ids
    return ids.toMutableList().apply { add(to, removeAt(from)) }
}

/** One id a line: neither a component name nor a shortcut has a line break in it. */
fun encodeDockApps(ids: List<String>): String = ids.joinToString("\n")

/** Back from [encodeDockApps]; never saved before is the [DEFAULT_DOCK_APPS], and an emptied dock stays empty. */
fun decodeDockApps(saved: String?): List<String> =
    saved?.split('\n')?.filter { it.isNotEmpty() }?.distinct()?.take(MAX_DOCK_APPS) ?: DEFAULT_DOCK_APPS
