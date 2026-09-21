"""The made-up car: it must look like what the launcher writes, and add up."""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

from sample_data import HISTORY_DAYS, SampleCar, position_along_route

TZ = timezone(timedelta(hours=7))
UID = "sample-car"


class Clock:
    def __init__(self, at: float) -> None:
        self.at = at

    def __call__(self) -> float:
        return self.at


def by_kind(documents, kind):
    return {path: fields for path, fields in documents.items() if path.startswith(f"users/{UID}/{kind}/")}


def test_a_parked_car_finished_a_trip_forty_minutes_ago():
    clock = Clock(datetime(2026, 9, 21, 12, 0, tzinfo=TZ).timestamp())
    documents = SampleCar("parked", now=clock).documents()

    live = documents[f"users/{UID}/live/car"]
    assert live["moving"] is False and live["speedKmh"] == 0.0 and live["engine"] == {}

    trips = by_kind(documents, "trips").values()
    assert not any(trip["ongoing"] for trip in trips)
    latest = max(trips, key=lambda trip: trip["startedAt"])
    assert abs(latest["endedAt"] / 1000 - (clock.at - 40 * 60)) < 1
    assert live["position"] == latest["end"]


def test_the_days_add_up_to_the_trips():
    documents = SampleCar("parked", now=Clock(datetime(2026, 9, 21, 12, 0, tzinfo=TZ).timestamp())).documents()
    trips, days = by_kind(documents, "trips"), by_kind(documents, "days")

    assert len(days) > HISTORY_DAYS / 3  # commutes on weekdays, some weekend drives
    assert abs(sum(t["distanceKm"] for t in trips.values()) - sum(d["distanceKm"] for d in days.values())) < 0.5
    assert sum(d["trips"] for d in days.values()) == len(trips)
    for path, total in days.items():
        day = path.rsplit("/", 1)[-1]
        same_day = [t for t in trips.values() if t["day"] == day]
        assert total["trips"] == len(same_day)
        assert abs(total["distanceKm"] - sum(t["distanceKm"] for t in same_day)) < 0.05
        assert total["maxSpeedKmh"] == max(t["maxSpeedKmh"] for t in same_day)


def test_trips_have_every_field_the_integration_reads():
    documents = SampleCar("parked").documents()
    for trip in by_kind(documents, "trips").values():
        for field in ("startedAt", "endedAt", "ongoing", "day", "distanceKm", "movingSeconds", "maxSpeedKmh", "start", "end"):
            assert field in trip
        assert trip["endedAt"] > trip["startedAt"] and trip["distanceKm"] > 0


def test_the_history_is_the_same_every_time_for_the_same_seed():
    at = datetime(2026, 9, 21, 12, 0, tzinfo=TZ).timestamp()
    assert SampleCar("parked", now=Clock(at)).documents() == SampleCar("parked", now=Clock(at)).documents()


def test_a_driving_car_really_moves():
    clock = Clock(datetime(2026, 9, 21, 12, 0, tzinfo=TZ).timestamp())
    car = SampleCar("driving", now=clock)

    first = car.documents()
    clock.at += 120
    second = car.documents()

    live1, live2 = first[f"users/{UID}/live/car"], second[f"users/{UID}/live/car"]
    assert live1["moving"] and live2["moving"]
    assert live2["updatedAt"] - live1["updatedAt"] == 120_000
    assert live1["position"] != live2["position"]
    assert 30 < live1["speedKmh"] < 65 and live1["engine"]["rpm"] > 800

    ongoing = [t for t in by_kind(second, "trips").values() if t["ongoing"]]
    assert len(ongoing) == 1
    before = [t for t in by_kind(first, "trips").values() if t["ongoing"]][0]
    assert ongoing[0]["distanceKm"] > before["distanceKm"]
    assert ongoing[0]["endedAt"] > before["endedAt"]


def test_the_trip_in_progress_is_part_of_todays_total():
    clock = Clock(datetime(2026, 9, 21, 12, 0, tzinfo=TZ).timestamp())
    documents = SampleCar("driving", now=clock).documents()
    trip = next(t for t in by_kind(documents, "trips").values() if t["ongoing"])
    today = documents[f"users/{UID}/days/{trip['day']}"]
    assert today["distanceKm"] >= trip["distanceKm"] and today["trips"] >= 1


def test_the_route_stays_around_hanoi():
    for km in range(0, 500, 7):
        lat, lon = position_along_route(km)
        assert 20.95 < lat < 21.1 and 105.75 < lon < 105.9


def test_an_unknown_scenario_is_refused():
    try:
        SampleCar("flying")
    except ValueError:
        return
    raise AssertionError("expected ValueError")
