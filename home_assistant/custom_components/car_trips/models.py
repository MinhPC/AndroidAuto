"""What the launcher writes to Firestore, as Python objects.

The field names are the ones in the launcher's Schema.kt:

    users/{uid}/live/car             where the car is now
    users/{uid}/days/{yyyy-MM-dd}    the total of one local day
    users/{uid}/trips/{tripId}       one trip
    users/{uid}/refuels/{id}         one fill-up
"""

from __future__ import annotations

from dataclasses import dataclass, replace
from datetime import date, timedelta
from typing import Any
from urllib.parse import quote, urlencode

from .const import LIVE_STALE_SECONDS, ONGOING_STALE_SECONDS


def _number(data: dict[str, Any], key: str) -> float | None:
    value = data.get(key)
    return float(value) if isinstance(value, (int, float)) and not isinstance(value, bool) else None


def _int(data: dict[str, Any], key: str) -> int | None:
    value = _number(data, key)
    return None if value is None else int(value)


@dataclass(frozen=True)
class Engine:
    """What the engine said at the last moment the car reported; None where the car does not say."""

    rpm: int | None = None
    coolant_c: int | None = None
    intake_c: int | None = None
    load_percent: int | None = None
    throttle_percent: int | None = None
    fuel_trim_percent: int | None = None
    voltage: float | None = None


@dataclass(frozen=True)
class FuelLevel:
    """How much fuel the launcher thinks is left. The car does not say, so it counts down from the last full fill-up."""

    liters: float
    range_km: float
    percent: int | None  # None when the document does not say
    km_per_liter: float | None
    assumed: bool  # true until the driver's own economy is known and a typical one stands in for it


@dataclass(frozen=True)
class Live:
    latitude: float
    longitude: float
    speed_kmh: float
    moving: bool
    updated_at: float  # seconds since the epoch
    engine: Engine
    fuel: FuelLevel | None = None

    def settled(self, now: float) -> Live:
        """As it should be shown at [now]: a car not heard from for a while is not known to be moving."""
        if self.moving and now - self.updated_at > LIVE_STALE_SECONDS:
            return replace(self, moving=False, speed_kmh=0.0)
        return self


@dataclass(frozen=True)
class Trip:
    id: str
    started_at: float
    ended_at: float
    ongoing: bool
    distance_km: float
    moving_seconds: int
    max_speed_kmh: float
    start: tuple[float, float]
    end: tuple[float, float]
    max_coolant_c: int | None
    max_intake_c: int | None

    @property
    def avg_speed_kmh(self) -> float:
        """The average speed while moving, not counting the time spent standing still."""
        return self.distance_km / (self.moving_seconds / 3600) if self.moving_seconds > 0 else 0.0

    def settled(self, now: float) -> Trip:
        """A trip that has said nothing for a while is over, whatever it last said about itself."""
        if self.ongoing and now - self.ended_at > ONGOING_STALE_SECONDS:
            return replace(self, ongoing=False)
        return self


@dataclass(frozen=True)
class Totals:
    """The distance and effort over a stretch of days."""

    distance_km: float = 0.0
    trips: int = 0
    moving_seconds: int = 0
    max_speed_kmh: float = 0.0


def parse_live(data: dict[str, Any] | None) -> Live | None:
    if not data:
        return None
    position = data.get("position")
    if not isinstance(position, dict):
        return None
    lat, lon, updated = _number(position, "lat"), _number(position, "lon"), _number(data, "updatedAt")
    if lat is None or lon is None or updated is None:
        return None
    engine = data.get("engine") if isinstance(data.get("engine"), dict) else {}
    return Live(
        latitude=lat,
        longitude=lon,
        speed_kmh=_number(data, "speedKmh") or 0.0,
        moving=data.get("moving") is True,
        updated_at=updated / 1000,
        engine=Engine(
            rpm=_int(engine, "rpm"),
            coolant_c=_int(engine, "coolantC"),
            intake_c=_int(engine, "intakeC"),
            load_percent=_int(engine, "loadPercent"),
            throttle_percent=_int(engine, "throttlePercent"),
            fuel_trim_percent=_int(engine, "fuelTrimPercent"),
            voltage=_number(engine, "voltage"),
        ),
        fuel=_parse_fuel(data.get("fuel")),
    )


def _parse_fuel(data: Any) -> FuelLevel | None:
    if not isinstance(data, dict):
        return None
    liters, range_km, percent, economy = (_number(data, key) for key in ("liters", "rangeKm", "percent", "kmPerLiter"))
    if liters is None or range_km is None:
        return None
    return FuelLevel(
        liters=liters,
        range_km=range_km,
        percent=round(percent) if percent is not None else None,
        km_per_liter=economy,
        assumed=data.get("assumed") is True,
    )


def parse_trip(data: dict[str, Any] | None) -> Trip | None:
    if not data:
        return None
    started = _number(data, "startedAt")
    start, end = data.get("start"), data.get("end")
    if started is None or not isinstance(start, dict) or not isinstance(end, dict):
        return None
    start_lat, start_lon = _number(start, "lat"), _number(start, "lon")
    end_lat, end_lon = _number(end, "lat"), _number(end, "lon")
    if None in (start_lat, start_lon, end_lat, end_lon):
        return None
    return Trip(
        id=str(data.get("_id", "")),
        started_at=started / 1000,
        ended_at=(_number(data, "endedAt") or started) / 1000,
        ongoing=data.get("ongoing") is True,
        distance_km=_number(data, "distanceKm") or 0.0,
        moving_seconds=_int(data, "movingSeconds") or 0,
        max_speed_kmh=_number(data, "maxSpeedKmh") or 0.0,
        start=(start_lat, start_lon),  # type: ignore[arg-type]
        end=(end_lat, end_lon),  # type: ignore[arg-type]
        max_coolant_c=_int(data, "maxCoolantC"),
        max_intake_c=_int(data, "maxIntakeC"),
    )


def parse_day(data: dict[str, Any] | None) -> Totals:
    """A day's total; a day the car did not drive has no document and is zero."""
    if not data:
        return Totals()
    return Totals(
        distance_km=_number(data, "distanceKm") or 0.0,
        trips=_int(data, "trips") or 0,
        moving_seconds=_int(data, "movingSeconds") or 0,
        max_speed_kmh=_number(data, "maxSpeedKmh") or 0.0,
    )


@dataclass(frozen=True)
class Refuel:
    """One fill-up. The economy is only known for a full fill-up that had a full one before it."""

    id: str
    at: float  # seconds since the epoch
    liters: float
    amount_vnd: int
    full: bool
    distance_km: float | None
    liters_in_period: float | None

    @property
    def price_per_liter(self) -> int:
        return round(self.amount_vnd / self.liters) if self.liters > 0 else 0

    @property
    def km_per_liter(self) -> float | None:
        if self.distance_km and self.liters_in_period and self.distance_km > 0 and self.liters_in_period > 0:
            return self.distance_km / self.liters_in_period
        return None


@dataclass(frozen=True)
class FuelStats:
    """What the fill-ups say: the latest one, the economy, and what this month has cost."""

    last: Refuel | None = None
    last_economy: float | None = None  # km per litre at the latest fill-up that has one
    average_economy: float | None = None  # all their kilometres over all their litres
    month_cost_vnd: int = 0


def parse_refuel(data: dict[str, Any] | None) -> Refuel | None:
    if not data:
        return None
    at, liters = _number(data, "at"), _number(data, "liters")
    if at is None or liters is None or liters <= 0:
        return None
    return Refuel(
        id=str(data.get("_id", "")),
        at=at / 1000,
        liters=liters,
        amount_vnd=_int(data, "amountVnd") or 0,
        full=data.get("full") is True,
        distance_km=_number(data, "distanceKm"),
        liters_in_period=_number(data, "litersInPeriod"),
    )


def fuel_stats(refuels: list[Refuel], month_start: float, month_end: float) -> FuelStats:
    """Stats from [refuels] (newest first); the month runs from [month_start] up to [month_end], in epoch seconds."""
    measured = [r for r in refuels if r.km_per_liter is not None]
    liters = sum(r.liters_in_period or 0.0 for r in measured)
    return FuelStats(
        last=refuels[0] if refuels else None,
        last_economy=measured[0].km_per_liter if measured else None,
        average_economy=sum(r.distance_km or 0.0 for r in measured) / liters if liters > 0 else None,
        month_cost_vnd=sum(r.amount_vnd for r in refuels if month_start <= r.at < month_end),
    )


def totals_from_sums(sums: dict[str, float]) -> Totals:
    """Totals from the sums of a field over many days."""
    return Totals(
        distance_km=sums.get("distanceKm", 0.0),
        trips=int(sums.get("trips", 0)),
        moving_seconds=int(sums.get("movingSeconds", 0)),
    )


def totals_between(days: list[dict[str, Any]], first: date, last: date) -> Totals:
    """The total of the day documents (each with its date as "_id") from [first] to [last], both included."""
    low, high = first.isoformat(), last.isoformat()
    chosen = [day for day in days if low <= day.get("_id", "") <= high]
    return totals_from_sums(
        {name: sum(_number(day, name) or 0.0 for day in chosen) for name in ("distanceKm", "trips", "movingSeconds")}
    )


def chunk_seqs(last_seq: int, wanted: int) -> list[int]:
    """Up to [wanted] chunk numbers spread evenly between the first and the last (which are left out).

    A trip's route is stored in numbered chunks (about a minute each); reading them all would cost a read apiece,
    and a handful along the way is all a map needs to be steered along the road that was driven.
    """
    count = min(last_seq - 1, wanted)
    if count <= 0:
        return []
    return sorted({round(i * last_seq / (count + 1)) for i in range(1, count + 1)})


def chunk_midpoint(chunk: dict[str, Any] | None) -> tuple[float, float] | None:
    """The point in the middle of a route chunk (its points have their latitude under "a" and longitude under "o")."""
    points = chunk.get("points") if chunk else None
    if not isinstance(points, list) or not points:
        return None
    point = points[len(points) // 2]
    lat, lon = (_number(point, "a"), _number(point, "o")) if isinstance(point, dict) else (None, None)
    return (lat, lon) if lat is not None and lon is not None else None


def _coordinates(point: tuple[float, float]) -> str:
    return f"{point[0]:.6f},{point[1]:.6f}"


def google_maps_url(point: tuple[float, float]) -> str:
    """A link that opens Google Maps at [point]."""
    return "https://www.google.com/maps/search/?" + urlencode({"api": 1, "query": _coordinates(point)}, safe=",", quote_via=quote)


def google_maps_route_url(
    origin: tuple[float, float], destination: tuple[float, float], waypoints: list[tuple[float, float]]
) -> str:
    """A link that opens Google Maps with driving directions from [origin] to [destination] through [waypoints].

    Google works the road out between the points, so the line follows the roads and is close to, not exactly,
    the way the car went. Google takes no more than nine waypoints in such a link.
    """
    query = {"api": 1, "origin": _coordinates(origin), "destination": _coordinates(destination), "travelmode": "driving"}
    if waypoints:
        query["waypoints"] = "|".join(_coordinates(point) for point in waypoints[:9])
    return "https://www.google.com/maps/dir/?" + urlencode(query, safe=",", quote_via=quote)


def week_range(day: date) -> tuple[date, date]:
    """Monday to Sunday around [day]."""
    monday = day - timedelta(days=day.weekday())
    return monday, monday + timedelta(days=6)


def month_range(day: date) -> tuple[date, date]:
    first = day.replace(day=1)
    following = (first + timedelta(days=32)).replace(day=1)
    return first, following - timedelta(days=1)


def year_range(day: date) -> tuple[date, date]:
    return date(day.year, 1, 1), date(day.year, 12, 31)
