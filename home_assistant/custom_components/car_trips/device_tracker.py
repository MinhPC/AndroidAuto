"""The car on the map."""

from __future__ import annotations

from typing import Any

from homeassistant.components.device_tracker import SourceType, TrackerEntity
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddConfigEntryEntitiesCallback
from homeassistant.util import dt as dt_util

from .coordinator import CarTripsConfigEntry, CarTripsCoordinator
from .entity import CarTripsEntity
from .models import google_maps_url

PARALLEL_UPDATES = 0


async def async_setup_entry(
    hass: HomeAssistant, entry: CarTripsConfigEntry, async_add_entities: AddConfigEntryEntitiesCallback
) -> None:
    async_add_entities([CarTracker(entry.runtime_data)])


class CarTracker(CarTripsEntity, TrackerEntity):
    """The position the car last sent. Home Assistant works out home / not home from it as for a phone."""

    _attr_name = None  # the device is the car, so the entity takes its name

    def __init__(self, coordinator: CarTripsCoordinator) -> None:
        super().__init__(coordinator, "tracker")

    @property
    def available(self) -> bool:
        return super().available and self.coordinator.data.live is not None

    @property
    def source_type(self) -> SourceType:
        return SourceType.GPS

    @property
    def latitude(self) -> float | None:
        live = self.coordinator.data.live
        return live.latitude if live else None

    @property
    def longitude(self) -> float | None:
        live = self.coordinator.data.live
        return live.longitude if live else None

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        live = self.coordinator.data.live
        if live is None:
            return {}
        return {
            "speed": round(live.speed_kmh),
            "moving": live.moving,
            "last_seen": dt_util.utc_from_timestamp(live.updated_at).isoformat(),
            "google_maps_url": google_maps_url((live.latitude, live.longitude)),
        }
