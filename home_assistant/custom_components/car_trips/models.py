"""What the launcher writes to Firestore, as Python objects.

The field names are the ones in the launcher's Schema.kt:

    users/{uid}/live/car             where the car is now
    users/{uid}/days/{yyyy-MM-dd}    the total of one local day
    users/{uid}/trips/{tripId}       one trip
"""

from __future__ import annotations

import json
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
class Live:
    latitude: float
    longitude: float
    speed_kmh: float
    moving: bool
    updated_at: float  # seconds since the epoch
    engine: Engine

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


def route_geojson(chunks: list[dict[str, Any]], max_points: int) -> str | None:
    """Every point of the route across [chunks] (oldest chunk first), as a GeoJSON LineString for a map card that
    draws it - the actual GPS trace, not a road-snapped guess through a handful of waypoints like
    [google_maps_route_url]. Thinned to at most [max_points] evenly spread ones when there are more: still smooth
    to look at, lighter to store as an entity attribute and to hand to the browser. None with fewer than two points,
    which draws nothing.
    """
    points: list[tuple[float, float]] = []
    for chunk in chunks:
        for point in (chunk or {}).get("points") or []:
            lat, lon = (_number(point, "a"), _number(point, "o")) if isinstance(point, dict) else (None, None)
            if lat is not None and lon is not None:
                points.append((lat, lon))
    if len(points) < 2:
        return None
    if len(points) > max_points:
        step = len(points) / max_points
        points = [points[round(i * step)] for i in range(max_points)]
    return json.dumps(
        {
            "type": "Feature",
            "properties": {},
            "geometry": {"type": "LineString", "coordinates": [[lon, lat] for lat, lon in points]},
        }
    )


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
