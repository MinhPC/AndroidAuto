"""Setting up, and setting up again when the key stops working."""

from __future__ import annotations

import json
from unittest.mock import patch

import aiohttp
from homeassistant import config_entries
from homeassistant.data_entry_flow import FlowResultType
from pytest_homeassistant_custom_component.common import MockConfigEntry

from custom_components.car_trips.const import CONF_SERVICE_ACCOUNT, CONF_UID, DOMAIN

from .conftest import DOCS, TOKEN_URI, UID

LIVE = f"{DOCS}/users/{UID}/live/car"
TOKEN_OK = {"access_token": "tok", "expires_in": 3600}


async def start(hass):
    result = await hass.config_entries.flow.async_init(DOMAIN, context={"source": config_entries.SOURCE_USER})
    assert result["type"] is FlowResultType.FORM
    return result


def entry_input(service_account, uid=UID):
    return {CONF_SERVICE_ACCOUNT: json.dumps(service_account), CONF_UID: uid, "name": "Audi"}


async def test_a_working_key_creates_the_entry(hass, aioclient_mock, service_account):
    aioclient_mock.post(TOKEN_URI, json=TOKEN_OK)
    aioclient_mock.get(LIVE, status=404, json={"error": {"message": "not found"}})  # the car has sent nothing yet

    with patch("custom_components.car_trips.async_setup_entry", return_value=True):
        result = await hass.config_entries.flow.async_configure((await start(hass))["flow_id"], entry_input(service_account))
        await hass.async_block_till_done()

    assert result["type"] is FlowResultType.CREATE_ENTRY
    assert result["title"] == "Audi"
    assert result["data"][CONF_UID] == UID
    assert result["data"][CONF_SERVICE_ACCOUNT]["project_id"] == service_account["project_id"]


async def test_text_that_is_not_a_key_is_refused(hass, service_account):
    bad = {CONF_SERVICE_ACCOUNT: "hello", CONF_UID: UID}
    result = await hass.config_entries.flow.async_configure((await start(hass))["flow_id"], bad)
    assert result["type"] is FlowResultType.FORM
    assert result["errors"] == {CONF_SERVICE_ACCOUNT: "invalid_service_account"}


async def test_the_reasons_a_key_can_fail_are_told_apart(hass, aioclient_mock, service_account):
    cases = [
        (lambda: aioclient_mock.post(TOKEN_URI, status=400, json={"error": "invalid_grant"}), "invalid_auth"),
        (
            lambda: (
                aioclient_mock.post(TOKEN_URI, json=TOKEN_OK),
                aioclient_mock.get(LIVE, status=403, json={"error": {"message": "denied"}}),
            ),
            "no_permission",
        ),
        (
            lambda: (
                aioclient_mock.post(TOKEN_URI, json=TOKEN_OK),
                aioclient_mock.get(LIVE, status=500, json={"error": {"message": "oops"}}),
            ),
            "cannot_connect",
        ),
        (lambda: aioclient_mock.post(TOKEN_URI, exc=aiohttp.ClientError("down")), "cannot_connect"),
    ]
    for arrange, error in cases:
        aioclient_mock.clear_requests()
        arrange()
        flow = await start(hass)
        result = await hass.config_entries.flow.async_configure(flow["flow_id"], entry_input(service_account))
        assert result["type"] is FlowResultType.FORM
        assert result["errors"] == {"base": error}, error
        hass.config_entries.flow.async_abort(flow["flow_id"])


async def test_the_same_car_is_not_added_twice(hass, aioclient_mock, service_account):
    MockConfigEntry(domain=DOMAIN, unique_id=UID, data={CONF_SERVICE_ACCOUNT: service_account, CONF_UID: UID}).add_to_hass(hass)
    result = await hass.config_entries.flow.async_configure((await start(hass))["flow_id"], entry_input(service_account))
    assert result["type"] is FlowResultType.ABORT
    assert result["reason"] == "already_configured"


async def test_reauth_replaces_the_key(hass, aioclient_mock, service_account):
    entry = MockConfigEntry(domain=DOMAIN, unique_id=UID, data={CONF_SERVICE_ACCOUNT: {"old": True}, CONF_UID: UID})
    entry.add_to_hass(hass)
    aioclient_mock.post(TOKEN_URI, json=TOKEN_OK)
    aioclient_mock.get(LIVE, status=404, json={})

    flow = await entry.start_reauth_flow(hass)
    assert flow["step_id"] == "reauth_confirm"
    with patch("custom_components.car_trips.async_setup_entry", return_value=True):
        result = await hass.config_entries.flow.async_configure(
            flow["flow_id"], {CONF_SERVICE_ACCOUNT: json.dumps(service_account)}
        )
        await hass.async_block_till_done()

    assert result["type"] is FlowResultType.ABORT
    assert result["reason"] == "reauth_successful"
    assert entry.data[CONF_SERVICE_ACCOUNT]["project_id"] == service_account["project_id"]
