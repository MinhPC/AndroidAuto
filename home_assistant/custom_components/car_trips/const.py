"""Constants for the Car Trips integration."""

from __future__ import annotations

from datetime import timedelta

DOMAIN = "car_trips"

CONF_SERVICE_ACCOUNT = "service_account"
CONF_UID = "uid"

# While the car is driving the position is worth having fresh; parked, nothing changes and every read costs quota.
MOVING_INTERVAL = timedelta(seconds=30)
IDLE_INTERVAL = timedelta(minutes=5)

# The launcher refreshes the live position every 15 s while driving. One that is older than this has stopped
# coming (the car lost power mid-trip), so its "moving" is no longer true.
LIVE_STALE_SECONDS = 120

# The same for a trip still marked "ongoing": nothing has been added to it for this long, so it is over.
ONGOING_STALE_SECONDS = 600
