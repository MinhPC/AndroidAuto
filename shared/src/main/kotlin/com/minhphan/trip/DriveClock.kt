package com.minhphan.trip

/** One journey as the trip screen lists it: when it began and ended, and how long of it the car was on the move. */
data class DriveRecord(val startedAt: Long, val endedAt: Long, val movingSeconds: Long) {
    /** How long it lasted from start to end, nights and stops included. */
    val elapsedSeconds: Long get() = ((endedAt - startedAt) / 1000).coerceAtLeast(0)
}

/**
 * The journey in progress: when it began, when the car was last heard of, how long it has moved so far, and whether it
 * was on the move at the last reading ([moving]).
 */
data class OpenDrive(
    val startedAt: Long,
    val lastSeenAt: Long,
    val movingMs: Long,
    val moving: Boolean,
) {
    /** The moving time as of [now]: what was counted, and the time since the last reading if the car was moving then. */
    fun movingSecondsAt(now: Long): Long {
        val since = now - lastSeenAt
        val extra = if (moving && since in 0..DriveClock.MAX_GAP_MS) since else 0
        return (movingMs + extra) / 1000
    }

    /** How long it has lasted as of [now], nights and stops included. */
    fun elapsedSecondsAt(now: Long): Long = ((now - startedAt) / 1000).coerceAtLeast(0)
}

/**
 * The trip clock: the driver starts a journey ([start]) and ends it ([endNow]), and in between it counts the time
 * the car is on the move, however long the journey lasts: days, with the car parked and the head unit off at night.
 * Nothing starts or ends one by itself. The journeys ended are kept, newest first.
 *
 * It is fed readings ([onReading]) of whether the car is moving every second or two while a journey runs; it only
 * adds up the time between two readings when the first said moving and they are close enough together ([MAX_GAP_MS])
 * to be sure the car went on moving in between, so the hours the head unit was off count nothing.
 */
data class DriveClock(val current: OpenDrive? = null, val history: List<DriveRecord> = emptyList()) {

    /** Starts a journey now; one already running goes on. */
    fun start(now: Long): DriveClock = if (current != null) this else copy(current = OpenDrive(now, now, 0, false))

    fun onReading(now: Long, moving: Boolean): DriveClock {
        val open = current ?: return this
        val gap = now - open.lastSeenAt
        // A clock that went back, or a long silence, counts nothing; it takes up from the new time.
        val counted = if (open.moving && gap in 0..MAX_GAP_MS) gap else 0
        return copy(current = OpenDrive(open.startedAt, now, open.movingMs + counted, moving))
    }

    /** The driver ends the journey now, with what it came to kept at the top of the history. */
    fun endNow(now: Long): DriveClock {
        val open = current ?: return this
        val gap = now - open.lastSeenAt
        val movingMs = open.movingMs + if (open.moving && gap in 0..MAX_GAP_MS) gap else 0
        val record = DriveRecord(open.startedAt, maxOf(now, open.startedAt), movingMs / 1000)
        return DriveClock(current = null, history = (listOf(record) + history).take(MAX_HISTORY))
    }

    companion object {
        /** Readings further apart than this do not count the time between them: who knows what the car did. */
        const val MAX_GAP_MS = 15_000L

        /** How many journeys are kept. */
        const val MAX_HISTORY = 300
    }
}

/** The journeys as text for storage, "startedAt,endedAt,movingSeconds" each, ";" between them. */
fun encodeDrives(drives: List<DriveRecord>): String = drives.joinToString(";") { "${it.startedAt},${it.endedAt},${it.movingSeconds}" }

/** The journeys [encodeDrives] wrote; one that cannot be read is skipped. */
fun decodeDrives(text: String?): List<DriveRecord> =
    text.orEmpty().split(';').mapNotNull { item ->
        val parts = item.split(',')
        if (parts.size != 3) return@mapNotNull null
        val (start, end, moving) = parts.map { it.toLongOrNull() ?: return@mapNotNull null }
        DriveRecord(start, end, moving)
    }

/** The journey in progress as text for storage, or null for none. */
fun encodeOpenDrive(open: OpenDrive?): String? = open?.let { "${it.startedAt},${it.lastSeenAt},${it.movingMs},${it.moving}" }

fun decodeOpenDrive(text: String?): OpenDrive? {
    val parts = text?.split(',') ?: return null
    if (parts.size != 4) return null
    val numbers = parts.take(3).map { it.toLongOrNull() ?: return null }
    return OpenDrive(numbers[0], numbers[1], numbers[2], parts[3].toBooleanStrictOrNull() ?: return null)
}
