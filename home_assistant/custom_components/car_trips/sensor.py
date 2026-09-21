"""Sensors: distance by day, week, month and year, the last trip, and what the engine says right now."""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime
from typing import Any

from homeassistant.components.sensor import (
    SensorDeviceClass,
    SensorEntity,
    SensorEntityDescription,
    SensorStateClass,
)
from homeassistant.const import (
    PERCENTAGE,
    EntityCategory,
    UnitOfElectricPotential,
    UnitOfLength,
    UnitOfSpeed,
    UnitOfTemperature,
    UnitOfTime,
    UnitOfVolume,
)
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddConfigEntryEntitiesCallback
from homeassistant.helpers.typing import StateType
from homeassistant.util import dt as dt_util

from .coordinator import CarData, CarTripsConfigEntry, CarTripsCoordinator
from .entity import CarTripsEntity

PARALLEL_UPDATES = 0

RPM = "rpm"


@dataclass(frozen=True, kw_only=True)
class CarSensorDescription(SensorEntityDescription):
    value_fn: Callable[[CarData], StateType | datetime]
    attributes_fn: Callable[[CarData], dict[str, Any]] | None = None


def _minutes(seconds: int) -> float:
    return round(seconds / 60, 1)


def _time(seconds: float | None) -> datetime | None:
    return dt_util.utc_from_timestamp(seconds) if seconds else None


def _engine(read: Callable[[Any], StateType]) -> Callable[[CarData], StateType]:
    return lambda data: read(data.live.engine) if data.live else None


def _fuel(read: Callable[[Any], StateType | datetime]) -> Callable[[CarData], StateType | datetime]:
    """A value of the latest fill-up; unknown until the driver has logged one."""
    return lambda data: read(data.fuel.last) if data.fuel.last else None


def _trip(read: Callable[[Any], StateType | datetime]) -> Callable[[CarData], StateType | datetime]:
    return lambda data: read(data.last_trip) if data.last_trip else None


def _distance(key: str, read: Callable[[CarData], float]) -> CarSensorDescription:
    return CarSensorDescription(
        key=key,
        translation_key=key,
        device_class=SensorDeviceClass.DISTANCE,
        native_unit_of_measurement=UnitOfLength.KILOMETERS,
        # The total starts again each day, week, month and year; Home Assistant takes a drop as a new start.
        state_class=SensorStateClass.TOTAL_INCREASING,
        suggested_display_precision=1,
        value_fn=lambda data: round(read(data), 2),
    )


SENSORS: tuple[CarSensorDescription, ...] = (
    _distance("distance_today", lambda d: d.today.distance_km),
    _distance("distance_week", lambda d: d.week.distance_km),
    _distance("distance_month", lambda d: d.month.distance_km),
    _distance("distance_year", lambda d: d.year.distance_km),
    CarSensorDescription(
        key="trips_today",
        translation_key="trips_today",
        state_class=SensorStateClass.TOTAL_INCREASING,
        value_fn=lambda d: d.today.trips,
    ),
    CarSensorDescription(
        key="driving_time_today",
        translation_key="driving_time_today",
        device_class=SensorDeviceClass.DURATION,
        native_unit_of_measurement=UnitOfTime.MINUTES,
        state_class=SensorStateClass.TOTAL_INCREASING,
        value_fn=lambda d: _minutes(d.today.moving_seconds),
    ),
    CarSensorDescription(
        key="max_speed_today",
        translation_key="max_speed_today",
        device_class=SensorDeviceClass.SPEED,
        native_unit_of_measurement=UnitOfSpeed.KILOMETERS_PER_HOUR,
        state_class=SensorStateClass.MEASUREMENT,
        suggested_display_precision=0,
        value_fn=lambda d: round(d.today.max_speed_kmh),
    ),
    # What the car says right now. Parked, the launcher sends no engine data, and these read unknown.
    CarSensorDescription(
        key="speed",
        translation_key="speed",
        device_class=SensorDeviceClass.SPEED,
        native_unit_of_measurement=UnitOfSpeed.KILOMETERS_PER_HOUR,
        state_class=SensorStateClass.MEASUREMENT,
        suggested_display_precision=0,
        value_fn=lambda d: round(d.live.speed_kmh) if d.live else None,
    ),
    CarSensorDescription(
        key="engine_rpm",
        translation_key="engine_rpm",
        native_unit_of_measurement=RPM,
        state_class=SensorStateClass.MEASUREMENT,
        value_fn=_engine(lambda e: e.rpm),
    ),
    CarSensorDescription(
        key="coolant_temperature",
        translation_key="coolant_temperature",
        device_class=SensorDeviceClass.TEMPERATURE,
        native_unit_of_measurement=UnitOfTemperature.CELSIUS,
        state_class=SensorStateClass.MEASUREMENT,
        value_fn=_engine(lambda e: e.coolant_c),
    ),
    CarSensorDescription(
        key="intake_temperature",
        translation_key="intake_temperature",
        device_class=SensorDeviceClass.TEMPERATURE,
        native_unit_of_measurement=UnitOfTemperature.CELSIUS,
        state_class=SensorStateClass.MEASUREMENT,
        value_fn=_engine(lambda e: e.intake_c),
    ),
    CarSensorDescription(
        key="battery_voltage",
        translation_key="battery_voltage",
        device_class=SensorDeviceClass.VOLTAGE,
        native_unit_of_measurement=UnitOfElectricPotential.VOLT,
        state_class=SensorStateClass.MEASUREMENT,
        suggested_display_precision=1,
        value_fn=_engine(lambda e: e.voltage),
    ),
    CarSensorDescription(
        key="fuel_trim",
        translation_key="fuel_trim",
        native_unit_of_measurement=PERCENTAGE,
        state_class=SensorStateClass.MEASUREMENT,
        value_fn=_engine(lambda e: e.fuel_trim_percent),
    ),
    CarSensorDescription(
        key="engine_load",
        translation_key="engine_load",
        native_unit_of_measurement=PERCENTAGE,
        state_class=SensorStateClass.MEASUREMENT,
        entity_registry_enabled_default=False,
        value_fn=_engine(lambda e: e.load_percent),
    ),
    CarSensorDescription(
        key="throttle_position",
        translation_key="throttle_position",
        native_unit_of_measurement=PERCENTAGE,
        state_class=SensorStateClass.MEASUREMENT,
        entity_registry_enabled_default=False,
        value_fn=_engine(lambda e: e.throttle_percent),
    ),
    # The last trip: the one in progress while the car drives, the one just finished when it is parked.
    CarSensorDescription(
        key="last_trip_distance",
        translation_key="last_trip_distance",
        device_class=SensorDeviceClass.DISTANCE,
        native_unit_of_measurement=UnitOfLength.KILOMETERS,
        state_class=SensorStateClass.MEASUREMENT,
        suggested_display_precision=1,
        value_fn=_trip(lambda t: round(t.distance_km, 2)),
        attributes_fn=lambda d: (
            {
                "start_latitude": d.last_trip.start[0],
                "start_longitude": d.last_trip.start[1],
                "end_latitude": d.last_trip.end[0],
                "end_longitude": d.last_trip.end[1],
                "ongoing": d.last_trip.ongoing,
                # Opens Google Maps with the route; missing until the route has been read.
                **({"google_maps_route_url": d.route_url} if d.route_url else {}),
            }
            if d.last_trip
            else {}
        ),
    ),
    CarSensorDescription(
        key="last_trip_duration",
        translation_key="last_trip_duration",
        device_class=SensorDeviceClass.DURATION,
        native_unit_of_measurement=UnitOfTime.MINUTES,
        state_class=SensorStateClass.MEASUREMENT,
        value_fn=_trip(lambda t: _minutes(t.moving_seconds)),
    ),
    CarSensorDescription(
        key="last_trip_average_speed",
        translation_key="last_trip_average_speed",
        device_class=SensorDeviceClass.SPEED,
        native_unit_of_measurement=UnitOfSpeed.KILOMETERS_PER_HOUR,
        state_class=SensorStateClass.MEASUREMENT,
        suggested_display_precision=0,
        value_fn=_trip(lambda t: round(t.avg_speed_kmh)),
    ),
    CarSensorDescription(
        key="last_trip_max_speed",
        translation_key="last_trip_max_speed",
        device_class=SensorDeviceClass.SPEED,
        native_unit_of_measurement=UnitOfSpeed.KILOMETERS_PER_HOUR,
        state_class=SensorStateClass.MEASUREMENT,
        suggested_display_precision=0,
        value_fn=_trip(lambda t: round(t.max_speed_kmh)),
    ),
    CarSensorDescription(
        key="last_trip_start",
        translation_key="last_trip_start",
        device_class=SensorDeviceClass.TIMESTAMP,
        value_fn=_trip(lambda t: _time(t.started_at)),
    ),
    CarSensorDescription(
        key="last_trip_end",
        translation_key="last_trip_end",
        device_class=SensorDeviceClass.TIMESTAMP,
        value_fn=_trip(lambda t: None if t.ongoing else _time(t.ended_at)),
    ),
    CarSensorDescription(
        key="last_trip_max_coolant",
        translation_key="last_trip_max_coolant",
        device_class=SensorDeviceClass.TEMPERATURE,
        native_unit_of_measurement=UnitOfTemperature.CELSIUS,
        state_class=SensorStateClass.MEASUREMENT,
        entity_registry_enabled_default=False,
        value_fn=_trip(lambda t: t.max_coolant_c),
    ),
    CarSensorDescription(
        key="last_trip_max_intake",
        translation_key="last_trip_max_intake",
        device_class=SensorDeviceClass.TEMPERATURE,
        native_unit_of_measurement=UnitOfTemperature.CELSIUS,
        state_class=SensorStateClass.MEASUREMENT,
        entity_registry_enabled_default=False,
        value_fn=_trip(lambda t: t.max_intake_c),
    ),
    # The fuel book: what the driver logs at the pump on the launcher.
    CarSensorDescription(
        key="fuel_economy",
        translation_key="fuel_economy",
        native_unit_of_measurement="km/L",
        state_class=SensorStateClass.MEASUREMENT,
        suggested_display_precision=1,
        value_fn=lambda d: round(d.fuel.last_economy, 2) if d.fuel.last_economy is not None else None,
    ),
    CarSensorDescription(
        key="fuel_economy_average",
        translation_key="fuel_economy_average",
        native_unit_of_measurement="km/L",
        state_class=SensorStateClass.MEASUREMENT,
        suggested_display_precision=1,
        value_fn=lambda d: round(d.fuel.average_economy, 2) if d.fuel.average_economy is not None else None,
    ),
    CarSensorDescription(
        key="last_refuel_liters",
        translation_key="last_refuel_liters",
        device_class=SensorDeviceClass.VOLUME,
        native_unit_of_measurement=UnitOfVolume.LITERS,
        suggested_display_precision=1,
        value_fn=_fuel(lambda r: r.liters),
    ),
    CarSensorDescription(
        key="last_refuel_cost",
        translation_key="last_refuel_cost",
        device_class=SensorDeviceClass.MONETARY,
        native_unit_of_measurement="VND",
        suggested_display_precision=0,
        value_fn=_fuel(lambda r: r.amount_vnd),
    ),
    CarSensorDescription(
        key="last_refuel_price",
        translation_key="last_refuel_price",
        native_unit_of_measurement="VND/L",
        suggested_display_precision=0,
        value_fn=_fuel(lambda r: r.price_per_liter),
    ),
    CarSensorDescription(
        key="last_refuel_time",
        translation_key="last_refuel_time",
        device_class=SensorDeviceClass.TIMESTAMP,
        value_fn=_fuel(lambda r: _time(r.at)),
    ),
    CarSensorDescription(
        key="fuel_cost_month",
        translation_key="fuel_cost_month",
        device_class=SensorDeviceClass.MONETARY,
        native_unit_of_measurement="VND",
        suggested_display_precision=0,
        value_fn=lambda d: d.fuel.month_cost_vnd,
    ),
    CarSensorDescription(
        key="last_update",
        translation_key="last_update",
        device_class=SensorDeviceClass.TIMESTAMP,
        entity_category=EntityCategory.DIAGNOSTIC,
        value_fn=lambda d: _time(d.live.updated_at) if d.live else None,
    ),
)


async def async_setup_entry(
    hass: HomeAssistant, entry: CarTripsConfigEntry, async_add_entities: AddConfigEntryEntitiesCallback
) -> None:
    coordinator = entry.runtime_data
    async_add_entities(CarSensor(coordinator, description) for description in SENSORS)


class CarSensor(CarTripsEntity, SensorEntity):
    entity_description: CarSensorDescription

    def __init__(self, coordinator: CarTripsCoordinator, description: CarSensorDescription) -> None:
        super().__init__(coordinator, description.key)
        self.entity_description = description

    @property
    def native_value(self) -> StateType | datetime:
        return self.entity_description.value_fn(self.coordinator.data)

    @property
    def extra_state_attributes(self) -> dict[str, Any] | None:
        if self.entity_description.attributes_fn is None:
            return None
        return self.entity_description.attributes_fn(self.coordinator.data)
