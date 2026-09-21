"""The whole thing: Firestore answers in, entities out."""

from __future__ import annotations

import time

import pytest
from homeassistant.config_entries import SOURCE_REAUTH, ConfigEntryState
from homeassistant.core import HomeAssistant
from pytest_homeassistant_custom_component.common import MockConfigEntry
from pytest_homeassistant_custom_component.test_util.aiohttp import AiohttpClientMockResponse

from custom_components.car_trips.const import CONF_SERVICE_ACCOUNT, CONF_UID, DOMAIN, IDLE_INTERVAL, MOVING_INTERVAL

from .conftest import DOCS, TOKEN_URI, UID, fields

USER = f"{DOCS}/users/{UID}"
TOKEN_OK = {"access_token": "tok", "expires_in": 3600}
TODAY = "2026-09-21"

WEEK = {"distanceKm": {"doubleValue": 47.5}, "trips": {"integerValue": "4"}, "movingSeconds": {"integerValue": "5700"}}
MONTH = {"distanceKm": {"doubleValue": 210.25}, "trips": {"integerValue": "15"}, "movingSeconds": {"integerValue": "20000"}}
YEAR = {"distanceKm": {"doubleValue": 5400.0}, "trips": {"integerValue": "300"}, "movingSeconds": {"integerValue": "400000"}}


def now_ms(ago_seconds: float = 5) -> int:
    return int((time.time() - ago_seconds) * 1000)


def live_fields(**over):
    base = {
        "position": {"lat": 21.03, "lon": 105.85},
        "speedKmh": 54.2,
        "moving": True,
        "updatedAt": now_ms(),
        "engine": {"rpm": 2100, "coolantC": 88, "oilC": 95, "voltage": 14.1, "fuelPercent": 55},
    }
    return {**base, **over}


def trip_fields(**over):
    base = {
        "startedAt": now_ms(900),
        "endedAt": now_ms(5),
        "ongoing": True,
        "day": TODAY,
        "distanceKm": 12.5,
        "movingSeconds": 900,
        "maxSpeedKmh": 88.0,
        "start": {"lat": 21.0, "lon": 105.8},
        "end": {"lat": 21.03, "lon": 105.85},
        "maxCoolantC": 92,
    }
    return {**base, **over}


def arrange(aioclient_mock, *, live=None, trip=None, today=None):
    """Firestore answering as if the car had sent [live], [trip] and the day total [today] (None: nothing)."""
    aioclient_mock.post(TOKEN_URI, json=TOKEN_OK)
    if live is None:
        aioclient_mock.get(f"{USER}/live/car", status=404, json={})
    else:
        aioclient_mock.get(f"{USER}/live/car", json={"name": "x/car", **fields(**live)})
    if today is None:
        aioclient_mock.get(f"{USER}/days/{TODAY}", status=404, json={})
    else:
        aioclient_mock.get(f"{USER}/days/{TODAY}", json={"name": f"x/{TODAY}", **fields(**today)})
    trips = [{"document": {"name": "x/trips/1", **fields(**trip)}}] if trip else [{"readTime": "t"}]
    aioclient_mock.post(f"{USER}:runQuery", json=trips)

    async def aggregation(method, url, data):
        first = data["structuredAggregationQuery"]["structuredQuery"]["where"]["compositeFilter"]["filters"][0]
        first_day = first["fieldFilter"]["value"]["referenceValue"].rsplit("/", 1)[-1]
        sums = {"2026-09-21": WEEK, "2026-09-01": MONTH, "2026-01-01": YEAR}[first_day]
        return AiohttpClientMockResponse(method, url, json=[{"result": {"aggregateFields": sums}}])

    aioclient_mock.post(f"{USER}:runAggregationQuery", side_effect=aggregation)


@pytest.fixture(autouse=True)
def frozen_clock(freezer):
    """Noon on the 21st in Hanoi, before anything reads the clock: the fake car data is dated from it."""
    freezer.move_to("2026-09-21 12:00:00+07:00")


async def setup(hass: HomeAssistant, service_account, freezer) -> MockConfigEntry:
    await hass.config.async_set_time_zone("Asia/Ho_Chi_Minh")
    entry = MockConfigEntry(
        domain=DOMAIN, unique_id=UID, title="Car", data={CONF_SERVICE_ACCOUNT: service_account, CONF_UID: UID}
    )
    entry.add_to_hass(hass)
    await hass.config_entries.async_setup(entry.entry_id)
    await hass.async_block_till_done()
    return entry


def state(hass, entity_id):
    found = hass.states.get(entity_id)
    assert found is not None, entity_id
    return found


async def test_a_driving_car_shows_everything(hass, aioclient_mock, service_account, freezer):
    arrange(
        aioclient_mock,
        live=live_fields(),
        trip=trip_fields(),
        today={"distanceKm": 12.5, "trips": 1, "movingSeconds": 900, "maxSpeedKmh": 88.0},
    )
    entry = await setup(hass, service_account, freezer)
    assert entry.state is ConfigEntryState.LOADED

    tracker = state(hass, "device_tracker.car")
    assert (tracker.attributes["latitude"], tracker.attributes["longitude"]) == (21.03, 105.85)
    assert tracker.attributes["source_type"] == "gps" and tracker.attributes["speed"] == 54

    assert state(hass, "binary_sensor.car_driving").state == "on"
    assert state(hass, "sensor.car_speed").state == "54"
    assert state(hass, "sensor.car_engine_speed").state == "2100"
    assert state(hass, "sensor.car_coolant_temperature").state == "88"
    assert state(hass, "sensor.car_oil_temperature").state == "95"
    assert state(hass, "sensor.car_battery_voltage").state == "14.1"
    assert state(hass, "sensor.car_fuel_level").state == "55"

    assert state(hass, "sensor.car_distance_today").state == "12.5"
    assert state(hass, "sensor.car_distance_this_week").state == "47.5"
    assert state(hass, "sensor.car_distance_this_month").state == "210.25"
    assert state(hass, "sensor.car_distance_this_year").state == "5400.0"
    assert state(hass, "sensor.car_trips_today").state == "1"
    assert state(hass, "sensor.car_driving_time_today").state == "15.0"
    assert state(hass, "sensor.car_top_speed_today").state == "88"

    distance = state(hass, "sensor.car_last_trip_distance")
    assert distance.state == "12.5" and distance.attributes["ongoing"] is True
    assert distance.attributes["end_latitude"] == 21.03
    assert state(hass, "sensor.car_last_trip_average_speed").state == "50"
    assert state(hass, "sensor.car_last_trip_end").state == "unknown"  # still going

    assert entry.runtime_data.update_interval == MOVING_INTERVAL


async def test_a_parked_car_reads_off_and_is_polled_slowly(hass, aioclient_mock, service_account, freezer):
    arrange(
        aioclient_mock,
        live=live_fields(moving=False, speedKmh=0.0, engine={}),
        trip=trip_fields(ongoing=False),
        today={"distanceKm": 12.5, "trips": 1, "movingSeconds": 900, "maxSpeedKmh": 88.0},
    )
    entry = await setup(hass, service_account, freezer)

    assert state(hass, "binary_sensor.car_driving").state == "off"
    assert state(hass, "sensor.car_engine_speed").state == "unknown"
    assert state(hass, "sensor.car_last_trip_end").state != "unknown"
    assert state(hass, "device_tracker.car").attributes["latitude"] == 21.03  # it is still where it was
    assert entry.runtime_data.update_interval == IDLE_INTERVAL


async def test_a_car_that_lost_power_while_moving_is_not_left_driving(hass, aioclient_mock, service_account, freezer):
    arrange(
        aioclient_mock,
        live=live_fields(updatedAt=now_ms(3600)),
        trip=trip_fields(startedAt=now_ms(4000), endedAt=now_ms(3600)),
    )
    entry = await setup(hass, service_account, freezer)

    assert state(hass, "binary_sensor.car_driving").state == "off"
    assert state(hass, "sensor.car_speed").state == "0"
    assert state(hass, "sensor.car_last_trip_distance").attributes["ongoing"] is False
    assert entry.runtime_data.update_interval == IDLE_INTERVAL


async def test_before_the_car_has_ever_sent_anything(hass, aioclient_mock, service_account, freezer):
    arrange(aioclient_mock)
    entry = await setup(hass, service_account, freezer)

    assert entry.state is ConfigEntryState.LOADED
    assert state(hass, "device_tracker.car").state == "unavailable"
    assert state(hass, "binary_sensor.car_driving").state == "unknown"
    assert state(hass, "sensor.car_distance_today").state == "0.0"
    assert state(hass, "sensor.car_distance_this_week").state == "47.5"
    assert state(hass, "sensor.car_last_trip_distance").state == "unknown"


async def test_a_key_that_google_refuses_asks_for_a_new_one(hass, aioclient_mock, service_account, freezer):
    aioclient_mock.post(TOKEN_URI, status=400, json={"error": "invalid_grant", "error_description": "gone"})
    entry = await setup(hass, service_account, freezer)

    assert entry.state is ConfigEntryState.SETUP_ERROR
    assert any(flow["context"]["source"] == SOURCE_REAUTH for flow in hass.config_entries.flow.async_progress())


async def test_no_connection_is_retried_later(hass, aioclient_mock, service_account, freezer):
    aioclient_mock.post(TOKEN_URI, json=TOKEN_OK)
    aioclient_mock.get(f"{USER}/live/car", status=503, json={"error": {"message": "unavailable"}})
    aioclient_mock.get(f"{USER}/days/{TODAY}", json={})
    aioclient_mock.post(f"{USER}:runQuery", json=[])
    aioclient_mock.post(f"{USER}:runAggregationQuery", json=[])
    entry = await setup(hass, service_account, freezer)
    assert entry.state is ConfigEntryState.SETUP_RETRY


async def test_unloading_removes_the_entities(hass, aioclient_mock, service_account, freezer):
    arrange(aioclient_mock, live=live_fields())
    entry = await setup(hass, service_account, freezer)
    assert await hass.config_entries.async_unload(entry.entry_id)
    await hass.async_block_till_done()
    assert state(hass, "device_tracker.car").state == "unavailable"
