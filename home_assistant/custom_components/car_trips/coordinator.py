"""Reads the car's data from Firestore and shares it with the entities."""

from __future__ import annotations

import asyncio
import logging
import time
from dataclasses import dataclass
from datetime import date, timedelta
from typing import Any

from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import ConfigEntryAuthFailed
from homeassistant.helpers.update_coordinator import DataUpdateCoordinator, UpdateFailed
from homeassistant.util import dt as dt_util

from .const import DOMAIN, IDLE_INTERVAL, MOVING_INTERVAL
from .firestore import FirestoreAuthError, FirestoreClient, FirestoreError
from .models import (
    FuelStats,
    Live,
    Refuel,
    Totals,
    Trip,
    chunk_midpoint,
    chunk_seqs,
    fuel_stats,
    google_maps_route_url,
    month_range,
    parse_day,
    parse_live,
    parse_refuel,
    parse_trip,
    totals_between,
    week_range,
    year_range,
)

_LOGGER = logging.getLogger(__name__)

_SUMMED = ["distanceKm", "trips", "movingSeconds"]

# The days before today are read again this often, and when a new day starts: they change only when the car
# sends a trip it kept offline. Today is read on every poll.
HISTORY_MAX_AGE_SECONDS = 3600

# How many fill-ups are read to work out the economy and the month's cost.
REFUELS_KEPT = 20

# The route of a trip that is still going on is read again this often; a finished one is read once. Each read
# costs a look at the last chunk and one read for each waypoint, so about ten.
ROUTE_MAX_AGE_SECONDS = 300
ROUTE_WAYPOINTS = 8


# The newest chunk of a trip's route: its number says how many there are. Ids are numbers padded with zeros, so they sort as text.
_LAST_CHUNK_QUERY = {
    "from": [{"collectionId": "chunks"}],
    "orderBy": [{"field": {"fieldPath": "__name__"}, "direction": "DESCENDING"}],
    "limit": 1,
}


def _seq(chunk_id: str) -> int:
    return int(chunk_id) if chunk_id.isdigit() else 0


def _refuel_query(limit: int) -> dict[str, Any]:
    return {
        "from": [{"collectionId": "refuels"}],
        "orderBy": [{"field": {"fieldPath": "at"}, "direction": "DESCENDING"}],
        "limit": limit,
    }


def _month_bounds(month: tuple[date, date]) -> tuple[float, float]:
    """The month as epoch seconds, from its first midnight to the midnight after its last day, in Home Assistant's time zone."""
    first, last = month
    start = dt_util.start_of_local_day(first)
    end = dt_util.start_of_local_day(last + timedelta(days=1))
    return start.timestamp(), end.timestamp()


@dataclass(frozen=True)
class CarData:
    live: Live | None
    today: Totals
    week: Totals
    month: Totals
    year: Totals
    last_trip: Trip | None
    fuel: FuelStats
    route_url: str | None = None

    @property
    def driving(self) -> bool:
        """The car is moving, or the last trip is still going on."""
        return bool((self.live and self.live.moving) or (self.last_trip and self.last_trip.ongoing))


type CarTripsConfigEntry = ConfigEntry[CarTripsCoordinator]


class CarTripsCoordinator(DataUpdateCoordinator[CarData]):
    """Polls often while the car drives and rarely while it is parked, so the quota is not spent on nothing."""

    config_entry: CarTripsConfigEntry

    def __init__(self, hass: HomeAssistant, entry: CarTripsConfigEntry, client: FirestoreClient, uid: str) -> None:
        super().__init__(hass, _LOGGER, config_entry=entry, name=DOMAIN, update_interval=IDLE_INTERVAL)
        self.client = client
        self.uid = uid
        self._history: list[dict[str, Any]] = []
        self._history_day: date | None = None
        self._history_read_at = 0.0
        self._refuels: list[Refuel] = []
        self._refuels_newest: str | None = None
        self._refuels_read = False
        self._route_key: tuple[str, bool] | None = None
        self._route_read_at = 0.0
        self._route_link: str | None = None

    async def _route_url(self, user: str, trip: Trip | None) -> str | None:
        """A Google Maps link for the route of [trip]. Only worth a few reads once a trip is over, or now and then during one."""
        if trip is None:
            return None
        key = (trip.id, trip.ongoing)
        now = time.monotonic()
        if key == self._route_key and (not trip.ongoing or now - self._route_read_at < ROUTE_MAX_AGE_SECONDS):
            return self._route_link
        try:
            parent = f"{user}/trips/{trip.id}"
            last = await self.client.run_query(parent, _LAST_CHUNK_QUERY)
            seqs = chunk_seqs(_seq(last[0]["_id"]), ROUTE_WAYPOINTS) if last else []
            chunks = await asyncio.gather(*(self.client.get_document(f"{parent}/chunks/{seq:05d}") for seq in seqs))
        except FirestoreAuthError:
            raise
        except FirestoreError as err:
            # The route is a nicety: the rest of the data must not go missing because it could not be read.
            _LOGGER.debug("Cannot read the route of trip %s: %s", trip.id, err)
            return self._route_link if self._route_key and self._route_key[0] == trip.id else None
        waypoints = [point for chunk in chunks if (point := chunk_midpoint(chunk))]
        self._route_key, self._route_read_at = key, now
        self._route_link = google_maps_route_url(trip.start, trip.end, waypoints)
        return self._route_link

    async def _fill_ups(self, user: str) -> list[Refuel]:
        """The latest fill-ups. One read says whether there is a new one; only then are they all read again."""
        newest = await self.client.run_query(user, _refuel_query(1))
        newest_id = newest[0]["_id"] if newest else None
        if self._refuels_read and newest_id == self._refuels_newest:
            return self._refuels
        docs = await self.client.run_query(user, _refuel_query(REFUELS_KEPT)) if newest_id else []
        self._refuels = [refuel for doc in docs if (refuel := parse_refuel(doc))]
        self._refuels_newest, self._refuels_read = newest_id, True
        return self._refuels

    async def _past_days(self, user: str, day: date, first: date) -> list[dict[str, Any]]:
        """The day totals from [first] to yesterday. Firestore cannot add them up, so they are read and kept."""
        now = time.monotonic()
        if self._history_day == day and now - self._history_read_at < HISTORY_MAX_AGE_SECONDS:
            return self._history
        yesterday = day - timedelta(days=1)
        days = (
            await self.client.documents_between(user, "days", first.isoformat(), yesterday.isoformat(), _SUMMED)
            if first <= yesterday
            else []
        )
        self._history, self._history_day, self._history_read_at = days, day, now
        return days

    async def _async_update_data(self) -> CarData:
        user = f"users/{self.uid}"
        day = dt_util.now().date()
        week, month, year = week_range(day), month_range(day), year_range(day)

        try:
            live_doc, today_doc, trips, past_days, refuels = await asyncio.gather(
                self.client.get_document(f"{user}/live/car"),
                self.client.get_document(f"{user}/days/{day.isoformat()}"),
                self.client.run_query(
                    user,
                    {
                        "from": [{"collectionId": "trips"}],
                        "orderBy": [{"field": {"fieldPath": "startedAt"}, "direction": "DESCENDING"}],
                        "limit": 1,
                    },
                ),
                self._past_days(user, day, min(week[0], year[0])),
                self._fill_ups(user),
            )
        except FirestoreAuthError as err:
            raise ConfigEntryAuthFailed(str(err)) from err
        except FirestoreError as err:
            raise UpdateFailed(f"Cannot read Firestore: {err}") from err

        now = time.time()
        days = [*past_days, today_doc] if today_doc else past_days
        live = parse_live(live_doc)
        trip = parse_trip(trips[0]) if trips else None
        trip = trip.settled(now) if trip else None
        try:
            route_url = await self._route_url(user, trip)
        except FirestoreAuthError as err:
            raise ConfigEntryAuthFailed(str(err)) from err
        data = CarData(
            live=live.settled(now) if live else None,
            today=parse_day(today_doc),
            week=totals_between(days, *week),
            month=totals_between(days, *month),
            year=totals_between(days, *year),
            last_trip=trip,
            fuel=fuel_stats(refuels, *_month_bounds(month)),
            route_url=route_url,
        )
        self.update_interval = MOVING_INTERVAL if data.driving else IDLE_INTERVAL
        return data
