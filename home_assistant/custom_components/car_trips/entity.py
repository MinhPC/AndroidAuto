"""The base of every entity: one device, one coordinator."""

from __future__ import annotations

from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.update_coordinator import CoordinatorEntity

from .const import DOMAIN
from .coordinator import CarTripsCoordinator


class CarTripsEntity(CoordinatorEntity[CarTripsCoordinator]):
    _attr_has_entity_name = True

    def __init__(self, coordinator: CarTripsCoordinator, key: str) -> None:
        super().__init__(coordinator)
        entry = coordinator.config_entry
        self._attr_unique_id = f"{entry.entry_id}_{key}"
        self._attr_device_info = DeviceInfo(
            identifiers={(DOMAIN, coordinator.uid)},
            name=entry.title,
            manufacturer="Car Launcher",
            model="Trip sync",
        )
