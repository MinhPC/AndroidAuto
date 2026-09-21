"""The integration against the mock server over real HTTP: what the user will run."""

from __future__ import annotations

import aiohttp
import pytest
from aiohttp.test_utils import TestServer
from homeassistant.config_entries import ConfigEntryState
from pytest_homeassistant_custom_component.common import MockConfigEntry

from custom_components.car_trips.const import CONF_SERVICE_ACCOUNT, CONF_UID, DOMAIN
from custom_components.car_trips.firestore import FirestoreClient
from custom_components.car_trips.models import parse_live, parse_trip
from mock_firestore import make_app, service_account
from sample_data import SampleCar

UID = "sample-car"


@pytest.fixture
async def mock_server():
    """Starts the mock for a scenario; yields (base_url, car)."""
    servers = []

    async def start(scenario: str):
        car = SampleCar(scenario, uid=UID)
        server = TestServer(make_app(car))
        await server.start_server()
        servers.append(server)
        return f"http://127.0.0.1:{server.port}", car

    yield start
    for server in servers:
        await server.close()


async def test_the_client_reads_the_sample_car(mock_server):
    base, car = await mock_server("driving")
    account = service_account(base)
    async with aiohttp.ClientSession() as session:
        client = FirestoreClient(session, account)

        live = parse_live(await client.get_document(f"users/{UID}/live/car"))
        assert live.moving and 30 < live.speed_kmh < 65 and live.engine.rpm

        latest = await client.run_query(
            f"users/{UID}",
            {
                "from": [{"collectionId": "trips"}],
                "orderBy": [{"field": {"fieldPath": "startedAt"}, "direction": "DESCENDING"}],
                "limit": 1,
            },
        )
        trip = parse_trip(latest[0])
        assert trip.ongoing and trip.distance_km >= 15

        documents = car.documents()
        days = await client.documents_between(f"users/{UID}", "days", "2026-01-01", "2026-12-31", ["distanceKm", "trips"])
        year = [f for path, f in documents.items() if "/days/2026-" in path]
        assert sum(day["trips"] for day in days) == sum(day["trips"] for day in year)
        assert abs(sum(day["distanceKm"] for day in days) - sum(day["distanceKm"] for day in year)) < 0.01
        assert all(set(day) == {"_id", "distanceKm", "trips"} for day in days)

        assert await client.get_document(f"users/{UID}/days/1999-01-01") is None


async def test_the_mock_refuses_a_request_without_the_token(mock_server):
    base, _ = await mock_server("parked")
    async with aiohttp.ClientSession() as session:
        async with session.get(f"{base}/v1/projects/car-trips-mock/databases/(default)/documents/users/{UID}/live/car") as response:
            assert response.status == 401


async def test_the_mock_says_so_when_it_is_asked_for_more_than_it_knows(mock_server):
    base, _ = await mock_server("parked")
    async with aiohttp.ClientSession() as session:
        client = FirestoreClient(session, service_account(base))
        with pytest.raises(Exception, match="does not support"):
            await client.run_query(f"users/{UID}", {"from": [{"collectionId": "trips"}], "offset": 3})


@pytest.mark.parametrize(("scenario", "driving"), [("driving", "on"), ("parked", "off")])
async def test_home_assistant_shows_the_sample_car(hass, socket_enabled, mock_server, scenario, driving):
    base, _ = await mock_server(scenario)
    await hass.config.async_set_time_zone("Asia/Ho_Chi_Minh")
    entry = MockConfigEntry(
        domain=DOMAIN, unique_id=UID, title="Car", data={CONF_SERVICE_ACCOUNT: service_account(base), CONF_UID: UID}
    )
    entry.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entry.entry_id)
    await hass.async_block_till_done()

    assert entry.state is ConfigEntryState.LOADED
    assert hass.states.get("binary_sensor.car_driving").state == driving
    lat = hass.states.get("device_tracker.car").attributes["latitude"]
    assert 20.95 < lat < 21.1
    assert float(hass.states.get("sensor.car_distance_this_year").state) >= float(hass.states.get("sensor.car_distance_this_month").state)
    assert float(hass.states.get("sensor.car_distance_this_month").state) >= float(hass.states.get("sensor.car_distance_today").state)
    assert hass.states.get("sensor.car_last_trip_distance").attributes["ongoing"] is (scenario == "driving")
    assert (hass.states.get("sensor.car_engine_speed").state == "unknown") is (scenario == "parked")

    assert await hass.config_entries.async_unload(entry.entry_id)
