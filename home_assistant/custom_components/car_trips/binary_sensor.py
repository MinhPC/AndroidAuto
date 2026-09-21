"""Whether the car is driving."""

from __future__ import annotations

from homeassistant.components.binary_sensor import BinarySensorDeviceClass, BinarySensorEntity
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddConfigEntryEntitiesCallback

from .coordinator import CarTripsConfigEntry, CarTripsCoordinator
from .entity import CarTripsEntity

PARALLEL_UPDATES = 0


async def async_setup_entry(
    hass: HomeAssistant, entry: CarTripsConfigEntry, async_add_entities: AddConfigEntryEntitiesCallback
) -> None:
    async_add_entities([CarMoving(entry.runtime_data)])


class CarMoving(CarTripsEntity, BinarySensorEntity):
    """On while the car moves; off when it is parked, and unknown until the car has ever reported."""

    _attr_translation_key = "moving"
    _attr_device_class = BinarySensorDeviceClass.MOVING

    def __init__(self, coordinator: CarTripsCoordinator) -> None:
        super().__init__(coordinator, "moving")

    @property
    def is_on(self) -> bool | None:
        live = self.coordinator.data.live
        return None if live is None else live.moving
