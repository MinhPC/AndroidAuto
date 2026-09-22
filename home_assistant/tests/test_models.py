"""The parsing of what the launcher writes, and the period ranges."""

from __future__ import annotations

import json
from datetime import date

from custom_components.car_trips.const import LIVE_STALE_SECONDS, ONGOING_STALE_SECONDS
from custom_components.car_trips.firestore import decode_document, decode_value
from custom_components.car_trips.models import (
    month_range,
    parse_day,
    parse_live,
    parse_trip,
    chunk_midpoint,
    chunk_seqs,
    fuel_stats,
    google_maps_route_url,
    google_maps_url,
    parse_refuel,
    route_geojson,
    totals_between,
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
        "engine": {"rpm": 2100, "coolantC": 88, "voltage": 14.1, "fuelTrimPercent": -3},
    }
    return {**base, **over}


def test_parse_live_reads_the_position_and_engine():
    live = parse_live(live_doc())
    assert (live.latitude, live.longitude) == (21.03, 105.85)
    assert live.speed_kmh == 54.2 and live.moving
    assert live.updated_at == 1_000_000_000
    assert live.engine.rpm == 2100 and live.engine.coolant_c == 88
    assert live.engine.voltage == 14.1 and live.engine.fuel_trim_percent == -3
    assert live.engine.intake_c is None
    assert live.fuel is None


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
    assert trip.distance_km == 12.5 and trip.max_coolant_c == 92 and trip.max_intake_c is None
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


def test_totals_between_adds_up_only_the_days_in_the_range():
    days = [
        {"_id": "2026-09-20", "distanceKm": 10.0, "trips": 1, "movingSeconds": 600},
        {"_id": "2026-09-21", "distanceKm": 5.5, "trips": 2, "movingSeconds": 300},
        {"_id": "2026-09-27", "distanceKm": 1.0, "trips": 1},
        {"_id": "2026-09-28", "distanceKm": 99.0, "trips": 9, "movingSeconds": 9},
    ]
    week = totals_between(days, date(2026, 9, 21), date(2026, 9, 27))
    assert (week.distance_km, week.trips, week.moving_seconds) == (6.5, 3, 300)
    assert totals_between([], date(2026, 9, 21), date(2026, 9, 27)).trips == 0


def refuel_doc(**over):
    base = {"_id": "1", "at": 1_000_000_000_000, "liters": 35.0, "amountVnd": 805_000, "full": True}
    return {**base, **over}


def test_parse_refuel_reads_a_fill_up_and_works_out_its_price_and_economy():
    refuel = parse_refuel(refuel_doc(distanceKm=490.0, litersInPeriod=35.0))
    assert refuel.at == 1_000_000_000 and refuel.liters == 35.0 and refuel.amount_vnd == 805_000 and refuel.full
    assert refuel.price_per_liter == 23_000
    assert refuel.km_per_liter == 14.0


def test_a_fill_up_without_a_period_has_no_economy_and_a_broken_one_is_skipped():
    assert parse_refuel(refuel_doc()).km_per_liter is None
    assert parse_refuel(refuel_doc(distanceKm=0.0, litersInPeriod=30.0)).km_per_liter is None
    assert parse_refuel(refuel_doc(liters=0)) is None
    assert parse_refuel({"_id": "2", "liters": 10.0}) is None
    assert parse_refuel(None) is None


def test_fuel_stats_take_the_average_over_all_the_litres_not_over_the_ratios():
    newest_first = [
        parse_refuel(refuel_doc(_id="3", at=3_000_000, distanceKm=490.0, litersInPeriod=35.0, amountVnd=800_000)),
        parse_refuel(refuel_doc(_id="2", at=2_000_000, distanceKm=390.0, litersInPeriod=30.0, amountVnd=700_000)),
        parse_refuel(refuel_doc(_id="1", at=1_000_000, amountVnd=900_000)),
    ]
    stats = fuel_stats(newest_first, month_start=1_500.0, month_end=4_000.0)

    assert stats.last.id == "3"
    assert stats.last_economy == 14.0
    assert round(stats.average_economy, 3) == round(880 / 65, 3)
    assert stats.month_cost_vnd == 800_000 + 700_000


def test_no_fill_ups_means_no_stats_and_no_cost():
    stats = fuel_stats([], 0.0, 1.0)
    assert stats.last is None and stats.last_economy is None and stats.average_economy is None
    assert stats.month_cost_vnd == 0


def test_chunk_seqs_are_spread_between_the_first_and_the_last_and_leave_both_out():
    assert chunk_seqs(0, 8) == [] and chunk_seqs(1, 8) == []
    assert chunk_seqs(2, 8) == [1]
    assert chunk_seqs(5, 8) == [1, 2, 3, 4]
    assert chunk_seqs(100, 8) == [11, 22, 33, 44, 56, 67, 78, 89]
    assert chunk_seqs(100, 3) == [25, 50, 75]


def test_chunk_midpoint_is_the_point_in_the_middle_and_tolerates_a_broken_chunk():
    points = [{"a": 21.0, "o": 105.0}, {"a": 21.1, "o": 105.1}, {"a": 21.2, "o": 105.2}]
    assert chunk_midpoint({"points": points}) == (21.1, 105.1)
    assert chunk_midpoint({"points": []}) is None
    assert chunk_midpoint({"points": [{"a": 21.0}]}) is None
    assert chunk_midpoint({"points": ["x"]}) is None
    assert chunk_midpoint(None) is None


def test_google_maps_links():
    assert google_maps_url((21.03, 105.85)) == "https://www.google.com/maps/search/?api=1&query=21.030000,105.850000"

    plain = google_maps_route_url((21.0, 105.8), (21.03, 105.85), [])
    assert plain == (
        "https://www.google.com/maps/dir/?api=1&origin=21.000000,105.800000"
        "&destination=21.030000,105.850000&travelmode=driving"
    )
    through = google_maps_route_url((21.0, 105.8), (21.03, 105.85), [(21.01, 105.81), (21.02, 105.83)])
    assert through.endswith("&waypoints=21.010000,105.810000%7C21.020000,105.830000")

    many = google_maps_route_url((21.0, 105.8), (21.03, 105.85), [(21.0 + i / 100, 105.8) for i in range(12)])
    assert many.count("%7C") == 8  # Google takes nine waypoints at most


def test_route_geojson_is_none_with_fewer_than_two_points():
    assert route_geojson([], 500) is None
    assert route_geojson([{"points": [{"a": 21.0, "o": 105.0}]}], 500) is None


def test_route_geojson_is_every_point_in_order_when_under_the_limit():
    chunks = [{"points": [{"a": 21.0 + i * 0.0001, "o": 105.0} for i in range(12)]} for _ in range(3)]
    data = json.loads(route_geojson(chunks, 500))
    assert data["type"] == "Feature"
    assert data["geometry"]["type"] == "LineString"
    coords = data["geometry"]["coordinates"]
    assert len(coords) == 36
    assert coords[0] == [105.0, 21.0]  # GeoJSON is [lon, lat]
    assert [c[1] for c in coords] == sorted(c[1] for c in coords)


def test_route_geojson_thins_evenly_when_over_the_limit():
    points, n = [], 0
    for _ in range(10):
        chunk_points = []
        for _ in range(100):
            chunk_points.append({"a": 21.0 + n * 0.00001, "o": 105.0})
            n += 1
        points.append({"points": chunk_points})
    coords = json.loads(route_geojson(points, 100))["geometry"]["coordinates"]
    assert len(coords) == 100
    lats = [c[1] for c in coords]
    assert lats == sorted(lats)
    assert lats[0] == 21.0


def test_route_geojson_tolerates_a_missing_chunk_and_a_broken_point():
    chunks = [None, {"points": [{"a": 1.0, "o": 2.0}, "x", {"a": 1.1}, {"a": 1.2, "o": 2.2}]}]
    coords = json.loads(route_geojson(chunks, 500))["geometry"]["coordinates"]
    assert coords == [[2.0, 1.0], [2.2, 1.2]]


def test_parse_live_reads_the_fuel_estimate_and_ignores_a_broken_one():
    fuel = parse_live(live_doc(fuel={"liters": 28.4, "rangeKm": 341.0, "percent": 57, "kmPerLiter": 12.0, "assumed": True})).fuel
    assert (fuel.liters, fuel.range_km, fuel.percent, fuel.km_per_liter, fuel.assumed) == (28.4, 341.0, 57, 12.0, True)
    assert parse_live(live_doc(fuel={"percent": 57})).fuel is None
    partial = parse_live(live_doc(fuel={"liters": 28.4, "rangeKm": 341.0})).fuel  # no percent, no economy: unknown, not 0
    assert partial.percent is None and partial.km_per_liter is None and partial.range_km == 341.0
    assert parse_live(live_doc(fuel="full")).fuel is None
