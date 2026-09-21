"""The parsing of what the launcher writes, and the period ranges."""

from __future__ import annotations

from datetime import date

from custom_components.car_trips.const import LIVE_STALE_SECONDS, ONGOING_STALE_SECONDS
from custom_components.car_trips.firestore import decode_document, decode_value
from custom_components.car_trips.models import (
    month_range,
    parse_day,
    parse_live,
    parse_trip,
    totals_from_sums,
    week_range,
    year_range,
)

from .conftest import fields


def test_decode_value_covers_the_firestore_types():
    assert decode_value({"nullValue": None}) is None
    assert decode_value({"booleanValue": True}) is True
    assert decode_value({"integerValue": "42"}) == 42
    assert decode_value({"doubleValue": 1.5}) == 1.5
    assert decode_value({"doubleValue": "NaN"} ) != decode_value({"doubleValue": "NaN"})  # NaN, not an error
    assert decode_value({"stringValue": "x"}) == "x"
    assert decode_value({"timestampValue": "2026-09-21T08:00:00Z"}) == "2026-09-21T08:00:00Z"
    assert decode_value({"arrayValue": {"values": [{"integerValue": "1"}, {"stringValue": "a"}]}}) == [1, "a"]
    assert decode_value({"arrayValue": {}}) == []
    assert decode_value({"mapValue": {"fields": {"a": {"integerValue": "1"}}}}) == {"a": 1}
    assert decode_value({"mapValue": {}}) == {}


def test_decode_document_keeps_the_id():
    document = {"name": "projects/p/databases/(default)/documents/users/u/days/2026-09-21", **fields(distanceKm=1.5)}
    assert decode_document(document) == {"distanceKm": 1.5, "_id": "2026-09-21"}


def live_doc(**over):
    base = {
        "position": {"lat": 21.03, "lon": 105.85},
        "speedKmh": 54.2,
        "moving": True,
        "updatedAt": 1_000_000_000_000,
        "engine": {"rpm": 2100, "coolantC": 88, "voltage": 14.1, "fuelPercent": 55},
    }
    return {**base, **over}


def test_parse_live_reads_the_position_and_engine():
    live = parse_live(live_doc())
    assert (live.latitude, live.longitude) == (21.03, 105.85)
    assert live.speed_kmh == 54.2 and live.moving
    assert live.updated_at == 1_000_000_000
    assert live.engine.rpm == 2100 and live.engine.coolant_c == 88
    assert live.engine.voltage == 14.1 and live.engine.fuel_percent == 55
    assert live.engine.oil_c is None


def test_parse_live_needs_a_position_and_a_time():
    assert parse_live(None) is None
    assert parse_live({}) is None
    assert parse_live(live_doc(position={"lat": 1.0})) is None
    assert parse_live({k: v for k, v in live_doc().items() if k != "updatedAt"}) is None


def test_a_moving_car_not_heard_from_is_no_longer_moving():
    live = parse_live(live_doc())
    assert live.settled(live.updated_at + LIVE_STALE_SECONDS).moving
    stale = live.settled(live.updated_at + LIVE_STALE_SECONDS + 1)
    assert not stale.moving and stale.speed_kmh == 0.0
    assert stale.latitude == live.latitude  # where it was is still true


def trip_doc(**over):
    base = {
        "_id": "1000000000000",
        "startedAt": 1_000_000_000_000,
        "endedAt": 1_000_000_600_000,
        "ongoing": False,
        "distanceKm": 12.5,
        "movingSeconds": 900,
        "maxSpeedKmh": 88.0,
        "start": {"lat": 21.0, "lon": 105.8},
        "end": {"lat": 21.1, "lon": 105.9},
        "maxCoolantC": 92,
    }
    return {**base, **over}


def test_parse_trip_and_its_average_speed():
    trip = parse_trip(trip_doc())
    assert trip.id == "1000000000000"
    assert trip.started_at == 1_000_000_000 and trip.ended_at == 1_000_000_600
    assert trip.distance_km == 12.5 and trip.max_coolant_c == 92 and trip.max_oil_c is None
    assert trip.avg_speed_kmh == 50.0  # 12.5 km in 15 minutes
    assert trip.start == (21.0, 105.8) and trip.end == (21.1, 105.9)
    assert parse_trip(trip_doc(movingSeconds=0)).avg_speed_kmh == 0.0


def test_parse_trip_rejects_what_is_incomplete():
    assert parse_trip(None) is None
    assert parse_trip(trip_doc(start=None)) is None
    assert parse_trip({k: v for k, v in trip_doc().items() if k != "startedAt"}) is None


def test_a_trip_that_went_quiet_is_over():
    trip = parse_trip(trip_doc(ongoing=True))
    assert trip.settled(trip.ended_at + ONGOING_STALE_SECONDS).ongoing
    assert not trip.settled(trip.ended_at + ONGOING_STALE_SECONDS + 1).ongoing


def test_parse_day_and_sums():
    day = parse_day({"distanceKm": 30.5, "trips": 2, "movingSeconds": 3600, "maxSpeedKmh": 90.0})
    assert (day.distance_km, day.trips, day.moving_seconds, day.max_speed_kmh) == (30.5, 2, 3600, 90.0)
    assert parse_day(None).distance_km == 0.0  # a day without driving has no document

    totals = totals_from_sums({"distanceKm": 47.5, "trips": 4.0, "movingSeconds": 5700.0})
    assert (totals.distance_km, totals.trips, totals.moving_seconds) == (47.5, 4, 5700)
    assert totals_from_sums({}).trips == 0


def test_ranges_are_monday_to_sunday_whole_months_and_years():
    wednesday = date(2026, 9, 23)
    assert week_range(wednesday) == (date(2026, 9, 21), date(2026, 9, 27))
    assert week_range(date(2026, 9, 27)) == (date(2026, 9, 21), date(2026, 9, 27))
    assert month_range(wednesday) == (date(2026, 9, 1), date(2026, 9, 30))
    assert month_range(date(2028, 2, 10)) == (date(2028, 2, 1), date(2028, 2, 29))
    assert month_range(date(2026, 12, 31)) == (date(2026, 12, 1), date(2026, 12, 31))
    assert year_range(wednesday) == (date(2026, 1, 1), date(2026, 12, 31))
