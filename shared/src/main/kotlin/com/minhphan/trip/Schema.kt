package com.minhphan.trip

/**
 * Where everything lives in Firestore, all under the signed-in user:
 *
 *     users/{uid}/days/{yyyy-MM-dd}            one [DayTotal] per local day
 *     users/{uid}/trips/{tripId}               one [TripSummary] per trip
 *     users/{uid}/trips/{tripId}/chunks/{seq}  the route, [TripPoint]s a minute or so at a time
 *     users/{uid}/live/car                     the [LiveStatus]
 *     users/{uid}/refuels/{id}                 one [Refuel] per fill-up
 *
 * The maps are what is stored. Firestore hands numbers back as Long or Double whatever was written, so they are
 * read through [Number] and never cast.
 */
object Schema {
    const val USERS = "users"
    const val DAYS = "days"
    const val TRIPS = "trips"
    const val CHUNKS = "chunks"
    const val LIVE = "live"
    const val REFUELS = "refuels"
    const val LIVE_DOC = "car"
    const val POINTS = "points"

    /** Chunks are numbered so they sort as text: 00000, 00001, ... */
    fun chunkId(seq: Int): String = seq.toString().padStart(5, '0')
}

private fun Any?.int(): Int? = (this as? Number)?.toInt()
private fun Any?.long(): Long? = (this as? Number)?.toLong()
private fun Any?.double(): Double? = (this as? Number)?.toDouble()
private fun Any?.float(): Float? = (this as? Number)?.toFloat()

@Suppress("UNCHECKED_CAST")
private fun Any?.map(): Map<String, Any?>? = this as? Map<String, Any?>

private fun Map<String, Any?>.withIfNotNull(key: String, value: Any?): Map<String, Any?> =
    if (value == null) this else this + (key to value)

private fun LatLon.toMap(): Map<String, Any?> = mapOf("lat" to lat, "lon" to lon)

private fun latLonFrom(map: Map<String, Any?>?): LatLon? {
    if (map == null) return null
    val lat = map["lat"].double() ?: return null
    val lon = map["lon"].double() ?: return null
    return LatLon(lat, lon)
}

private fun EngineData.toMap(): Map<String, Any?> = emptyMap<String, Any?>()
    .withIfNotNull("rpm", rpm)
    .withIfNotNull("coolantC", coolantC)
    .withIfNotNull("intakeC", intakeC)
    .withIfNotNull("loadPercent", loadPercent)
    .withIfNotNull("throttlePercent", throttlePercent)
    .withIfNotNull("fuelTrimPercent", fuelTrimPercent)
    .withIfNotNull("voltage", voltage)

private fun engineFrom(map: Map<String, Any?>?) = EngineData(
    rpm = map?.get("rpm").int(),
    coolantC = map?.get("coolantC").int(),
    intakeC = map?.get("intakeC").int(),
    loadPercent = map?.get("loadPercent").int(),
    throttlePercent = map?.get("throttlePercent").int(),
    fuelTrimPercent = map?.get("fuelTrimPercent").int(),
    voltage = map?.get("voltage").float(),
)

fun TripSummary.toMap(): Map<String, Any?> = mapOf(
    "startedAt" to startedAt,
    "endedAt" to endedAt,
    "ongoing" to ongoing,
    "day" to day,
    "distanceKm" to distanceKm,
    "movingSeconds" to movingSeconds,
    "maxSpeedKmh" to maxSpeedKmh,
    "start" to start.toMap(),
    "end" to end.toMap(),
    "pointCount" to pointCount,
)
    .withIfNotNull("maxRpm", maxRpm)
    .withIfNotNull("maxCoolantC", maxCoolantC)
    .withIfNotNull("maxIntakeC", maxIntakeC)
    .withIfNotNull("minVoltage", minVoltage)
    .withIfNotNull("maxVoltage", maxVoltage)

fun tripSummaryFrom(id: String, map: Map<String, Any?>): TripSummary? {
    val startedAt = map["startedAt"].long() ?: return null
    return TripSummary(
        id = id,
        startedAt = startedAt,
        endedAt = map["endedAt"].long() ?: startedAt,
        ongoing = map["ongoing"] as? Boolean ?: false,
        day = map["day"] as? String ?: return null,
        distanceKm = map["distanceKm"].double() ?: 0.0,
        movingSeconds = map["movingSeconds"].long() ?: 0,
        maxSpeedKmh = map["maxSpeedKmh"].float() ?: 0f,
        start = latLonFrom(map["start"].map()) ?: return null,
        end = latLonFrom(map["end"].map()) ?: return null,
        maxRpm = map["maxRpm"].int(),
        maxCoolantC = map["maxCoolantC"].int(),
        maxIntakeC = map["maxIntakeC"].int(),
        minVoltage = map["minVoltage"].float(),
        maxVoltage = map["maxVoltage"].float(),
        pointCount = map["pointCount"].int() ?: 0,
    )
}

fun DayTotal.toMap(): Map<String, Any?> = mapOf(
    "distanceKm" to distanceKm,
    "trips" to trips,
    "movingSeconds" to movingSeconds,
    "maxSpeedKmh" to maxSpeedKmh,
)

fun dayTotalFrom(day: String, map: Map<String, Any?>) = DayTotal(
    day = day,
    distanceKm = map["distanceKm"].double() ?: 0.0,
    trips = map["trips"].int() ?: 0,
    movingSeconds = map["movingSeconds"].long() ?: 0,
    maxSpeedKmh = map["maxSpeedKmh"].float() ?: 0f,
)

/** A point is stored with one-letter names: a long trip has thousands of them. */
fun TripPoint.toMap(): Map<String, Any?> = mapOf(
    "t" to timeMs,
    "a" to position.lat,
    "o" to position.lon,
    "v" to speedKmh,
)
    .withIfNotNull("r", engine.rpm)
    .withIfNotNull("c", engine.coolantC)
    .withIfNotNull("i", engine.intakeC)
    .withIfNotNull("l", engine.loadPercent)
    .withIfNotNull("h", engine.throttlePercent)
    .withIfNotNull("f", engine.fuelTrimPercent)
    .withIfNotNull("b", engine.voltage)

fun tripPointFrom(map: Map<String, Any?>): TripPoint? {
    val time = map["t"].long() ?: return null
    val lat = map["a"].double() ?: return null
    val lon = map["o"].double() ?: return null
    return TripPoint(
        timeMs = time,
        position = LatLon(lat, lon),
        speedKmh = map["v"].float() ?: 0f,
        engine = EngineData(
            rpm = map["r"].int(),
            coolantC = map["c"].int(),
            intakeC = map["i"].int(),
            loadPercent = map["l"].int(),
            throttlePercent = map["h"].int(),
            fuelTrimPercent = map["f"].int(),
            voltage = map["b"].float(),
        ),
    )
}

fun LiveStatus.toMap(): Map<String, Any?> = mapOf(
    "position" to position.toMap(),
    "speedKmh" to speedKmh,
    "moving" to moving,
    "updatedAt" to updatedAt,
    "engine" to engine.toMap(),
)

fun liveStatusFrom(map: Map<String, Any?>): LiveStatus? {
    val position = latLonFrom(map["position"].map()) ?: return null
    val updatedAt = map["updatedAt"].long() ?: return null
    return LiveStatus(
        position = position,
        speedKmh = map["speedKmh"].float() ?: 0f,
        moving = map["moving"] as? Boolean ?: false,
        updatedAt = updatedAt,
        engine = engineFrom(map["engine"].map()),
    )
}

fun Refuel.toMap(): Map<String, Any?> = mapOf(
    "at" to at,
    "liters" to liters,
    "amountVnd" to amountVnd,
    "pricePerLiter" to pricePerLiter,
    "full" to full,
)
    .withIfNotNull("distanceKm", distanceKm)
    .withIfNotNull("litersInPeriod", litersInPeriod)
    .withIfNotNull("kmPerLiter", kmPerLiter)

fun refuelFrom(id: String, map: Map<String, Any?>): Refuel? {
    val at = map["at"].long() ?: return null
    val liters = map["liters"].double() ?: return null
    return Refuel(
        id = id,
        at = at,
        liters = liters,
        amountVnd = map["amountVnd"].long() ?: 0,
        full = map["full"] as? Boolean ?: false,
        distanceKm = map["distanceKm"].double(),
        litersInPeriod = map["litersInPeriod"].double(),
    )
}
