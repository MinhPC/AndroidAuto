"""A made-up car, in the shape the launcher writes it, for trying the integration without a real one.

Two scenarios:

* ``parked``: 200 days of commutes and weekend drives behind it, the last trip finished 40 minutes ago.
* ``driving``: the same history, and a trip in progress: the car really moves along a route around Hanoi,
  faster and slower, so the map, the speed and the engine sensors move each time Home Assistant asks.

Everything is worked out from the clock at the moment of asking, so it never goes stale.
"""

from __future__ import annotations

import math
import random
import time
from collections.abc import Callable
from datetime import datetime, timedelta, timezone
from typing import Any

HOME = (21.0285, 105.8542)  # Hoan Kiem
OFFICE = (21.0367, 105.7900)  # Cau Giay

# A loop for the driving scenario: home, out to Cau Giay, across Ba Dinh, back.
ROUTE = [HOME, (21.0245, 105.8412), (21.0313, 105.8194), OFFICE, (21.0459, 105.8069), (21.0417, 105.8358), HOME]

HISTORY_DAYS = 200
RECENT_TRIP_ENDED_MINUTES_AGO = 40
ONGOING_TRIP_STARTED_MINUTES_AGO = 18


def _km(a: tuple[float, float], b: tuple[float, float]) -> float:
    lat1, lon1, lat2, lon2 = map(math.radians, (*a, *b))
    h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin((lon2 - lon1) / 2) ** 2
    return 2 * 6371.0 * math.asin(math.sqrt(h))


def _route_length() -> float:
    return sum(_km(a, b) for a, b in zip(ROUTE, ROUTE[1:]))


def position_along_route(distance_km: float) -> tuple[float, float]:
    """Where a car that has driven [distance_km] along the looped route is."""
    remaining = distance_km % _route_length()
    for a, b in zip(ROUTE, ROUTE[1:]):
        leg = _km(a, b)
        if remaining <= leg:
            fraction = remaining / leg if leg else 0.0
            return a[0] + (b[0] - a[0]) * fraction, a[1] + (b[1] - a[1]) * fraction
        remaining -= leg
    return ROUTE[-1]


def driven_km(seconds: float) -> float:
    """Distance after [seconds] at a speed that swings between 33 and 63 km/h every four minutes or so."""
    return (48 * seconds + 15 * 40 * (1 - math.cos(seconds / 40))) / 3600


def speed_kmh(seconds: float) -> float:
    return 48 + 15 * math.sin(seconds / 40)


def _trip(started: float, ended: float, distance_km: float, start: tuple, end: tuple, rng: random.Random) -> dict[str, Any]:
    moving = int(ended - started - rng.randint(0, 120))
    return {
        "id": str(int(started * 1000)),
        "startedAt": int(started * 1000),
        "endedAt": int(ended * 1000),
        "ongoing": False,
        "distanceKm": round(distance_km, 2),
        "movingSeconds": max(moving, 60),
        "maxSpeedKmh": float(rng.randint(52, 88)),
        "start": {"lat": start[0], "lon": start[1]},
        "end": {"lat": end[0], "lon": end[1]},
        "maxRpm": rng.randint(2800, 4200),
        "maxCoolantC": rng.randint(86, 95),
        "maxIntakeC": rng.randint(38, 52),
        "minVoltage": round(rng.uniform(13.4, 13.9), 1),
        "maxVoltage": round(rng.uniform(14.1, 14.5), 1),
        "pointCount": max(int((ended - started) / 5), 12),
    }


class SampleCar:
    """The documents of one car, as {path: fields}, at the moment of asking."""

    def __init__(
        self,
        scenario: str = "parked",
        *,
        uid: str = "sample-car",
        utc_offset_hours: float = 7,
        now: Callable[[], float] = time.time,
        seed: int = 7,
    ) -> None:
        if scenario not in ("parked", "driving"):
            raise ValueError(f"unknown scenario {scenario!r}")
        self.scenario = scenario
        self.uid = uid
        self._now = now
        self._tz = timezone(timedelta(hours=utc_offset_hours))
        self._started = now()
        self._history = self._make_history(random.Random(seed))

    # -- the past -----------------------------------------------------------------------------------------

    def _make_history(self, rng: random.Random) -> list[dict[str, Any]]:
        t0 = self._started
        # Nothing generated from the calendar may overlap the trips added by hand near the present.
        cutoff = t0 - 90 * 60
        today = datetime.fromtimestamp(t0, self._tz).date()
        trips: list[dict[str, Any]] = []
        for ago in range(HISTORY_DAYS, -1, -1):
            day = today - timedelta(days=ago)
            midnight = datetime(day.year, day.month, day.day, tzinfo=self._tz).timestamp()
            plans: list[tuple[float, tuple, tuple, float]] = []  # (start second of the day, from, to, km)
            if day.weekday() < 5:
                if rng.random() < 0.9:
                    plans.append((7 * 3600 + 35 * 60 + rng.randint(-600, 600), HOME, OFFICE, rng.uniform(9, 16)))
                    plans.append((17 * 3600 + 40 * 60 + rng.randint(-1200, 1200), OFFICE, HOME, rng.uniform(9, 16)))
            elif rng.random() < 0.55:
                far = (HOME[0] + rng.uniform(-0.25, 0.25), HOME[1] + rng.uniform(-0.25, 0.25))
                plans.append((9 * 3600 + 30 * 60 + rng.randint(0, 5 * 3600), HOME, far, rng.uniform(15, 70)))
            for start_second, origin, destination, km in plans:
                started = midnight + start_second
                ended = started + km / rng.uniform(24, 34) * 3600
                if ended < cutoff:
                    trips.append(_trip(started, ended, km, origin, destination, rng))

        if self.scenario == "parked":
            ended = t0 - RECENT_TRIP_ENDED_MINUTES_AGO * 60
            trips.append(_trip(ended - 22 * 60, ended, 14.2, OFFICE, HOME, rng))
        return trips

    # -- now ----------------------------------------------------------------------------------------------

    def _ongoing_trip(self, now: float) -> dict[str, Any]:
        elapsed = now - self._started
        started = self._started - ONGOING_TRIP_STARTED_MINUTES_AGO * 60
        km = 15.0 + driven_km(elapsed)
        here = position_along_route(km)
        return {
            "id": str(int(started * 1000)),
            "startedAt": int(started * 1000),
            "endedAt": int(now * 1000),
            "ongoing": True,
            "distanceKm": round(km, 2),
            "movingSeconds": int(ONGOING_TRIP_STARTED_MINUTES_AGO * 60 + elapsed),
            "maxSpeedKmh": 72.0,
            "start": {"lat": HOME[0], "lon": HOME[1]},
            "end": {"lat": here[0], "lon": here[1]},
            "maxRpm": 3600,
            "maxCoolantC": 90,
            "maxIntakeC": 44,
            "minVoltage": 13.8,
            "maxVoltage": 14.3,
            "pointCount": int((ONGOING_TRIP_STARTED_MINUTES_AGO * 60 + elapsed) / 5),
        }

    def _live(self, now: float) -> dict[str, Any]:
        if self.scenario == "parked":
            last = max(self._history, key=lambda t: t["startedAt"])
            return {
                "position": dict(last["end"]),
                "speedKmh": 0.0,
                "moving": False,
                "updatedAt": int((self._started - 5 * 60) * 1000),
                "engine": {},  # the launcher sends no engine data while parked
            }
        elapsed = now - self._started
        km = 15.0 + driven_km(elapsed)
        here = position_along_route(km)
        speed = speed_kmh(elapsed)
        return {
            "position": {"lat": here[0], "lon": here[1]},
            "speedKmh": round(speed, 1),
            "moving": True,
            "updatedAt": int(now * 1000),
            "engine": {
                "rpm": int(800 + speed * 32),
                "coolantC": min(90, 70 + int((ONGOING_TRIP_STARTED_MINUTES_AGO * 60 + elapsed) / 60)),
                "intakeC": min(46, 32 + int((ONGOING_TRIP_STARTED_MINUTES_AGO * 60 + elapsed) / 90)),
                "voltage": 14.1,
                "fuelTrimPercent": int(3 * math.sin(elapsed / 60)),
                "loadPercent": int(25 + speed / 2),
                "throttlePercent": int(12 + speed / 4),
            },
        }

    def _days(self, trips: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
        days: dict[str, dict[str, Any]] = {}
        for trip in trips:
            day = datetime.fromtimestamp(trip["startedAt"] / 1000, self._tz).date().isoformat()
            total = days.setdefault(day, {"distanceKm": 0.0, "trips": 0, "movingSeconds": 0, "maxSpeedKmh": 0.0})
            total["distanceKm"] = round(total["distanceKm"] + trip["distanceKm"], 2)
            total["trips"] += 1
            total["movingSeconds"] += trip["movingSeconds"]
            total["maxSpeedKmh"] = max(total["maxSpeedKmh"], trip["maxSpeedKmh"])
        return days

    def documents(self) -> dict[str, dict[str, Any]]:
        """Every document of the car, keyed by its path."""
        now = self._now()
        trips = list(self._history)
        if self.scenario == "driving":
            trips.append(self._ongoing_trip(now))
        user = f"users/{self.uid}"
        documents: dict[str, dict[str, Any]] = {f"{user}/live/car": self._live(now)}
        for trip in trips:
            fields = {k: v for k, v in trip.items() if k != "id"}
            fields["day"] = datetime.fromtimestamp(trip["startedAt"] / 1000, self._tz).date().isoformat()
            documents[f"{user}/trips/{trip['id']}"] = fields
        for day, total in self._days(trips).items():
            documents[f"{user}/days/{day}"] = total
        for ago_days, liters, distance in ((35, 38.0, None), (21, 36.5, 480.0), (8, 34.0, 455.0)):
            at = int((now - ago_days * 86400) * 1000)
            refuel: dict[str, Any] = {
                "at": at,
                "liters": liters,
                "amountVnd": round(liters * 23_400),
                "pricePerLiter": 23_400,
                "full": True,
            }
            if distance is not None:
                refuel |= {"distanceKm": distance, "litersInPeriod": liters, "kmPerLiter": round(distance / liters, 3)}
            documents[f"{user}/refuels/{at}"] = refuel
        return documents
