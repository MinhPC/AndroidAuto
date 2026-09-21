"""Reads the car's data from Firestore and shares it with the entities."""

from __future__ import annotations

import asyncio
import logging
import time
from dataclasses import dataclass

from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import ConfigEntryAuthFailed
from homeassistant.helpers.update_coordinator import DataUpdateCoordinator, UpdateFailed
from homeassistant.util import dt as dt_util

from .const import DOMAIN, IDLE_INTERVAL, MOVING_INTERVAL
from .firestore import FirestoreAuthError, FirestoreClient, FirestoreError
from .models import (
    Live,
    Totals,
    Trip,
    month_range,
    parse_day,
    parse_live,
    parse_trip,
    totals_from_sums,
    week_range,
    year_range,
)

_LOGGER = logging.getLogger(__name__)

_SUMMED = ["distanceKm", "trips", "movingSeconds"]


@dataclass(frozen=True)
class CarData:
    live: Live | None
    today: Totals
    week: Totals
    month: Totals
    year: Totals
    last_trip: Trip | None

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

    async def _async_update_data(self) -> CarData:
        user = f"users/{self.uid}"
        day = dt_util.now().date()
        week, month, year = week_range(day), month_range(day), year_range(day)

        try:
            live_doc, today_doc, trips, week_sums, month_sums, year_sums = await asyncio.gather(
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
                *(
                    self.client.sum_range(user, "days", first.isoformat(), last.isoformat(), _SUMMED)
                    for first, last in (week, month, year)
                ),
            )
        except FirestoreAuthError as err:
            raise ConfigEntryAuthFailed(str(err)) from err
        except FirestoreError as err:
            raise UpdateFailed(f"Cannot read Firestore: {err}") from err

        now = time.time()
        live = parse_live(live_doc)
        trip = parse_trip(trips[0]) if trips else None
        data = CarData(
            live=live.settled(now) if live else None,
            today=parse_day(today_doc),
            week=totals_from_sums(week_sums),
            month=totals_from_sums(month_sums),
            year=totals_from_sums(year_sums),
            last_trip=trip.settled(now) if trip else None,
        )
        self.update_interval = MOVING_INTERVAL if data.driving else IDLE_INTERVAL
        return data
