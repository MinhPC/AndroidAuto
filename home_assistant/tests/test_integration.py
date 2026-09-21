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

# The days before today, as Firestore answers a query for them (the 21st is a Monday, so none are in this week).
PAST_DAYS = {
    "2026-09-10": {"distanceKm": 100.0, "trips": 8, "movingSeconds": 10000},
    "2026-03-15": {"distanceKm": 5000.0, "trips": 280, "movingSeconds": 300000},
}


def now_ms(ago_seconds: float = 5) -> int:
    return int((time.time() - ago_seconds) * 1000)


def live_fields(**over):
    base = {
        "position": {"lat": 21.03, "lon": 105.85},
        "speedKmh": 54.2,
        "moving": True,
        "updatedAt": now_ms(),
        "engine": {"rpm": 2100, "coolantC": 88, "intakeC": 41, "voltage": 14.1, "fuelTrimPercent": -3},
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


def fill_ups():
    """Three fill-ups, newest first: the last two measured (14.0 and 13.0 km/L), the first only a starting point."""
    return [
        ("r3", {"at": now_ms(2 * 86400), "liters": 35.0, "amountVnd": 805_000, "full": True, "distanceKm": 490.0, "litersInPeriod": 35.0}),
        ("r2", {"at": now_ms(16 * 86400), "liters": 30.0, "amountVnd": 690_000, "full": True, "distanceKm": 390.0, "litersInPeriod": 30.0}),
        ("r1", {"at": now_ms(32 * 86400), "liters": 40.0, "amountVnd": 900_000, "full": True}),
    ]


def arrange(aioclient_mock, *, live=None, trip=None, today=None, refuels=(), chunks=None, route_status=200):
    """Firestore answering as if the car had sent [live], [trip], the day total [today] (None: nothing) and [refuels]."""
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
    past = [{"document": {"name": f"x/days/{day}", **fields(**total)}} for day, total in PAST_DAYS.items()]

    async def query(method, url, data):
        structured = data["structuredQuery"]
        collection = structured["from"][0]["collectionId"]
        if collection == "refuels":
            found = [{"document": {"name": f"x/refuels/{name}", **fields(**doc)}} for name, doc in refuels]
            return AiohttpClientMockResponse(method, url, json=found[: structured["limit"]] or [{"readTime": "t"}])
        return AiohttpClientMockResponse(method, url, json=trips if collection == "trips" else past)

    aioclient_mock.post(f"{USER}:runQuery", side_effect=query)

    # The route of the trip (its id is 1): the last chunk, and the chunks the integration picks along the way.
    chunks = chunks or {}
    if chunks:
        last = max(chunks)
        newest = [{"document": {"name": f"x/trips/1/chunks/{last:05d}", **fields(points=chunks[last])}}]
    else:
        newest = [{"readTime": "t"}]
    if route_status == 200:
        aioclient_mock.post(f"{USER}/trips/1:runQuery", json=newest)
    else:
        aioclient_mock.post(f"{USER}/trips/1:runQuery", status=route_status, json={"error": {"message": "unavailable"}})
    for seq, points in chunks.items():
        aioclient_mock.get(f"{USER}/trips/1/chunks/{seq:05d}", json={"name": f"x/trips/1/chunks/{seq:05d}", **fields(points=points)})


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
    assert state(hass, "sensor.car_intake_temperature").state == "41"
    assert state(hass, "sensor.car_battery_voltage").state == "14.1"
    assert state(hass, "sensor.car_fuel_trim").state == "-3"

    assert state(hass, "sensor.car_distance_today").state == "12.5"
    assert state(hass, "sensor.car_distance_this_week").state == "12.5"
    assert state(hass, "sensor.car_distance_this_month").state == "112.5"
    assert state(hass, "sensor.car_distance_this_year").state == "5112.5"
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
    assert state(hass, "sensor.car_distance_this_week").state == "0.0"
    assert state(hass, "sensor.car_distance_this_month").state == "100.0"
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
    entry = await setup(hass, service_account, freezer)
    assert entry.state is ConfigEntryState.SETUP_RETRY


async def test_unloading_removes_the_entities(hass, aioclient_mock, service_account, freezer):
    arrange(aioclient_mock, live=live_fields())
    entry = await setup(hass, service_account, freezer)
    assert await hass.config_entries.async_unload(entry.entry_id)
    await hass.async_block_till_done()
    assert state(hass, "device_tracker.car").state == "unavailable"


async def test_the_past_days_are_read_once_an_hour_not_on_every_poll(hass, aioclient_mock, service_account, freezer):
    arrange(aioclient_mock, live=live_fields(), trip=trip_fields())
    entry = await setup(hass, service_account, freezer)

    def days_queries():
        return [
            call
            for call in aioclient_mock.mock_calls
            if call[0] == "POST" and str(call[1]).endswith(":runQuery") and call[2]["structuredQuery"]["from"][0]["collectionId"] == "days"
        ]

    assert len(days_queries()) == 1
    await entry.runtime_data.async_refresh()
    await entry.runtime_data.async_refresh()
    assert len(days_queries()) == 1
    assert state(hass, "sensor.car_distance_this_year").state == "5100.0"


async def test_the_fuel_book_shows_the_fill_ups(hass, aioclient_mock, service_account, freezer):
    arrange(aioclient_mock, live=live_fields(), trip=trip_fields(), refuels=fill_ups())
    await setup(hass, service_account, freezer)

    assert state(hass, "sensor.car_fuel_economy").state == "14.0"
    assert state(hass, "sensor.car_average_fuel_economy").state == "13.54"
    assert state(hass, "sensor.car_last_fill_up_litres").state == "35.0"
    assert state(hass, "sensor.car_last_fill_up_cost").state == "805000"
    assert state(hass, "sensor.car_last_fill_up_price_per_litre").state == "23000"
    assert state(hass, "sensor.car_fuel_cost_this_month").state == str(805_000 + 690_000)  # not last month's


async def test_without_fill_ups_the_fuel_sensors_are_unknown_and_the_month_costs_nothing(hass, aioclient_mock, service_account, freezer):
    arrange(aioclient_mock, live=live_fields(), trip=trip_fields())
    await setup(hass, service_account, freezer)

    assert state(hass, "sensor.car_fuel_economy").state == "unknown"
    assert state(hass, "sensor.car_last_fill_up_cost").state == "unknown"
    assert state(hass, "sensor.car_fuel_cost_this_month").state == "0"


async def test_all_the_fill_ups_are_read_again_only_when_there_is_a_new_one(hass, aioclient_mock, service_account, freezer):
    arrange(aioclient_mock, live=live_fields(), trip=trip_fields(), refuels=fill_ups())
    entry = await setup(hass, service_account, freezer)

    def refuel_queries(limit):
        return [
            call
            for call in aioclient_mock.mock_calls
            if call[0] == "POST"
            and str(call[1]).endswith(":runQuery")
            and call[2]["structuredQuery"]["from"][0]["collectionId"] == "refuels"
            and call[2]["structuredQuery"]["limit"] == limit
        ]

    await entry.runtime_data.async_refresh()
    await entry.runtime_data.async_refresh()
    assert len(refuel_queries(1)) == 3  # one look at the newest per poll
    assert len(refuel_queries(20)) == 1  # the whole list only once


ROUTE = {
    0: [{"t": 1, "a": 21.0, "o": 105.8, "v": 30.0}],
    1: [
        {"t": 2, "a": 21.005, "o": 105.81, "v": 40.0},
        {"t": 3, "a": 21.01, "o": 105.82, "v": 45.0},
        {"t": 4, "a": 21.015, "o": 105.83, "v": 40.0},
    ],
    2: [{"t": 5, "a": 21.03, "o": 105.85, "v": 35.0}],
}


def route_queries(aioclient_mock):
    return [call for call in aioclient_mock.mock_calls if call[0] == "POST" and str(call[1]).endswith("/trips/1:runQuery")]


async def test_the_last_trip_and_the_car_link_to_google_maps(hass, aioclient_mock, service_account, freezer):
    arrange(aioclient_mock, live=live_fields(), trip=trip_fields(), chunks=ROUTE)
    await setup(hass, service_account, freezer)

    assert state(hass, "sensor.car_last_trip_distance").attributes["google_maps_route_url"] == (
        "https://www.google.com/maps/dir/?api=1&origin=21.000000,105.800000&destination=21.030000,105.850000"
        "&travelmode=driving&waypoints=21.010000,105.820000"  # the middle of the middle chunk
    )
    assert state(hass, "device_tracker.car").attributes["google_maps_url"] == (
        "https://www.google.com/maps/search/?api=1&query=21.030000,105.850000"
    )


async def test_a_trip_without_a_stored_route_still_links_from_its_start_to_its_end(hass, aioclient_mock, service_account, freezer):
    arrange(aioclient_mock, live=live_fields(), trip=trip_fields())
    await setup(hass, service_account, freezer)

    assert state(hass, "sensor.car_last_trip_distance").attributes["google_maps_route_url"] == (
        "https://www.google.com/maps/dir/?api=1&origin=21.000000,105.800000&destination=21.030000,105.850000&travelmode=driving"
    )


async def test_no_trip_means_no_route_link(hass, aioclient_mock, service_account, freezer):
    arrange(aioclient_mock, live=live_fields())
    await setup(hass, service_account, freezer)

    assert "google_maps_route_url" not in state(hass, "sensor.car_last_trip_distance").attributes
    assert route_queries(aioclient_mock) == []


@pytest.mark.parametrize("ongoing", [True, False])
async def test_the_route_is_not_read_again_on_every_poll(hass, aioclient_mock, service_account, freezer, ongoing):
    arrange(aioclient_mock, live=live_fields(), trip=trip_fields(ongoing=ongoing), chunks=ROUTE)
    entry = await setup(hass, service_account, freezer)

    await entry.runtime_data.async_refresh()
    await entry.runtime_data.async_refresh()
    assert len(route_queries(aioclient_mock)) == 1


async def test_a_route_that_cannot_be_read_does_not_take_the_other_data_with_it(hass, aioclient_mock, service_account, freezer):
    arrange(aioclient_mock, live=live_fields(), trip=trip_fields(), chunks=ROUTE, route_status=500)
    entry = await setup(hass, service_account, freezer)

    assert entry.state is ConfigEntryState.LOADED
    assert state(hass, "sensor.car_last_trip_distance").state == "12.5"
    assert "google_maps_route_url" not in state(hass, "sensor.car_last_trip_distance").attributes
