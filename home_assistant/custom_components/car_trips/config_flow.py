"""Set-up: the service account key and the Firebase user id whose trips to read."""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any

import voluptuous as vol

from homeassistant.config_entries import ConfigFlow, ConfigFlowResult
from homeassistant.const import CONF_NAME
from homeassistant.core import HomeAssistant
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from homeassistant.helpers.selector import TextSelector, TextSelectorConfig

from .const import CONF_SERVICE_ACCOUNT, CONF_UID, DOMAIN
from .firestore import (
    FirestoreAuthError,
    FirestoreClient,
    FirestoreError,
    FirestorePermissionError,
    InvalidServiceAccount,
    parse_service_account,
)

KEY_SELECTOR = TextSelector(TextSelectorConfig(multiline=True))


async def _check(hass: HomeAssistant, service_account: dict[str, Any], uid: str) -> str | None:
    """None if the account can read the user's data, else the name of the error to show."""
    client = FirestoreClient(async_get_clientsession(hass), service_account)
    try:
        # A missing document is fine (the car may not have sent anything yet); a refusal is not.
        await client.get_document(f"users/{uid}/live/car")
    except FirestoreAuthError:
        return "invalid_auth"
    except FirestorePermissionError:
        return "no_permission"
    except FirestoreError:
        return "cannot_connect"
    return None


class CarTripsConfigFlow(ConfigFlow, domain=DOMAIN):
    VERSION = 1

    async def async_step_user(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        errors: dict[str, str] = {}
        if user_input is not None:
            uid = user_input[CONF_UID].strip()
            try:
                service_account = parse_service_account(user_input[CONF_SERVICE_ACCOUNT])
            except InvalidServiceAccount:
                errors[CONF_SERVICE_ACCOUNT] = "invalid_service_account"
            else:
                await self.async_set_unique_id(uid)
                self._abort_if_unique_id_configured()
                if error := await _check(self.hass, service_account, uid):
                    errors["base"] = error
                else:
                    return self.async_create_entry(
                        title=user_input.get(CONF_NAME) or "Car",
                        data={CONF_SERVICE_ACCOUNT: service_account, CONF_UID: uid},
                    )

        schema = vol.Schema(
            {
                vol.Required(CONF_SERVICE_ACCOUNT): KEY_SELECTOR,
                vol.Required(CONF_UID): str,
                vol.Optional(CONF_NAME, default="Car"): str,
            }
        )
        return self.async_show_form(
            step_id="user", data_schema=self.add_suggested_values_to_schema(schema, user_input), errors=errors
        )

    async def async_step_reauth(self, entry_data: Mapping[str, Any]) -> ConfigFlowResult:
        return await self.async_step_reauth_confirm()

    async def async_step_reauth_confirm(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        """The key stopped working (deleted, or replaced): ask for a new one."""
        errors: dict[str, str] = {}
        entry = self._get_reauth_entry()
        if user_input is not None:
            try:
                service_account = parse_service_account(user_input[CONF_SERVICE_ACCOUNT])
            except InvalidServiceAccount:
                errors[CONF_SERVICE_ACCOUNT] = "invalid_service_account"
            else:
                if error := await _check(self.hass, service_account, entry.data[CONF_UID]):
                    errors["base"] = error
                else:
                    return self.async_update_reload_and_abort(
                        entry, data_updates={CONF_SERVICE_ACCOUNT: service_account}
                    )
        return self.async_show_form(
            step_id="reauth_confirm",
            data_schema=vol.Schema({vol.Required(CONF_SERVICE_ACCOUNT): KEY_SELECTOR}),
            errors=errors,
        )
