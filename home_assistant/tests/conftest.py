"""Shared fixtures: a service account with a real (throwaway) RSA key, and canned Firestore answers."""

from __future__ import annotations

import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

import sys

pytest_plugins = "pytest_homeassistant_custom_component"

if sys.platform == "win32":
    # The test kit blocks sockets, but on Windows asyncio itself needs a loopback socket pair to run at all.
    import pytest_socket

    pytest_socket.disable_socket = lambda *args, **kwargs: None

    # And Home Assistant's DNS resolver refuses the default (proactor) loop. The test kit installs its own policy
    # when it is loaded and then disables set_event_loop_policy, so the policy is replaced directly, afterwards.
    import asyncio

    @pytest.hookimpl(trylast=True)
    def pytest_configure(config):
        asyncio.events._event_loop_policy = asyncio.WindowsSelectorEventLoopPolicy()

TOKEN_URI = "https://oauth2.googleapis.com/token"
PROJECT = "test-project"
UID = "user123"
DOCS = f"https://firestore.googleapis.com/v1/projects/{PROJECT}/databases/(default)/documents"


@pytest.fixture(autouse=True)
def auto_enable_custom_integrations(enable_custom_integrations):
    """Let Home Assistant load custom_components/ from this repository."""


@pytest.fixture(scope="session")
def rsa_key():
    return rsa.generate_private_key(public_exponent=65537, key_size=2048)


@pytest.fixture(scope="session")
def service_account(rsa_key) -> dict:
    pem = rsa_key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    ).decode()
    return {
        "type": "service_account",
        "project_id": PROJECT,
        "private_key_id": "key1",
        "private_key": pem,
        "client_email": f"ha@{PROJECT}.iam.gserviceaccount.com",
        "token_uri": TOKEN_URI,
    }


def fields(**values):
    """A Firestore REST document body from plain Python values."""

    def encode(v):
        if v is None:
            return {"nullValue": None}
        if isinstance(v, bool):
            return {"booleanValue": v}
        if isinstance(v, int):
            return {"integerValue": str(v)}
        if isinstance(v, float):
            return {"doubleValue": v}
        if isinstance(v, str):
            return {"stringValue": v}
        if isinstance(v, dict):
            return {"mapValue": {"fields": {k: encode(x) for k, x in v.items()}}}
        raise TypeError(v)

    return {"fields": {k: encode(v) for k, v in values.items()}}
