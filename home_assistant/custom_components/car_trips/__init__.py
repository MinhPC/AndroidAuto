"""Car Trips: the position, trips and engine data a car launcher sends to Firestore."""

from __future__ import annotations

from homeassistant.const import Platform
from homeassistant.core import HomeAssistant
from homeassistant.helpers.aiohttp_client import async_get_clientsession

from .const import CONF_SERVICE_ACCOUNT, CONF_UID
from .coordinator import CarTripsConfigEntry, CarTripsCoordinator
from .firestore import FirestoreClient

PLATFORMS = [Platform.BINARY_SENSOR, Platform.DEVICE_TRACKER, Platform.SENSOR]


async def async_setup_entry(hass: HomeAssistant, entry: CarTripsConfigEntry) -> bool:
    client = FirestoreClient(async_get_clientsession(hass), entry.data[CONF_SERVICE_ACCOUNT])
    coordinator = CarTripsCoordinator(hass, entry, client, entry.data[CONF_UID])
    await coordinator.async_config_entry_first_refresh()
    entry.runtime_data = coordinator
    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)
    return True


async def async_unload_entry(hass: HomeAssistant, entry: CarTripsConfigEntry) -> bool:
    return await hass.config_entries.async_unload_platforms(entry, PLATFORMS)
