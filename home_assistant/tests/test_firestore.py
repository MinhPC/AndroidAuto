"""The Firestore client: the token request, the reads and the ways they fail."""

from __future__ import annotations

import time

import aiohttp
import jwt
import pytest
from homeassistant.helpers.aiohttp_client import async_get_clientsession

from custom_components.car_trips.firestore import (
    FirestoreAuthError,
    FirestoreClient,
    FirestoreConnectionError,
    FirestoreError,
    FirestorePermissionError,
    InvalidServiceAccount,
    parse_service_account,
)

from .conftest import DOCS, PROJECT, TOKEN_URI, UID, fields

LIVE = f"{DOCS}/users/{UID}/live/car"
TOKEN_OK = {"access_token": "tok", "expires_in": 3600}


def make_client(hass, service_account, clock=time.time):
    return FirestoreClient(async_get_clientsession(hass), service_account, clock=clock)


def test_parse_service_account():
    key = '{"type":"service_account","client_email":"a@b","private_key":"k","project_id":"p"}'
    assert parse_service_account(key)["project_id"] == "p"
    for bad in ("", "not json", "[]", '{"type":"authorized_user"}', '{"type":"service_account","client_email":"a"}'):
        with pytest.raises(InvalidServiceAccount):
            parse_service_account(bad)


async def test_token_request_is_a_signed_jwt_the_key_owner_can_verify(hass, aioclient_mock, service_account, rsa_key):
    aioclient_mock.post(TOKEN_URI, json=TOKEN_OK)
    aioclient_mock.get(LIVE, json={"name": "x/live/car", **fields(moving=True)})

    document = await make_client(hass, service_account).get_document(f"users/{UID}/live/car")

    assert document == {"moving": True, "_id": "car"}
    method, url, data, headers = aioclient_mock.mock_calls[0]
    assert (method, str(url)) == ("POST", TOKEN_URI)
    assert data["grant_type"] == "urn:ietf:params:oauth:grant-type:jwt-bearer"
    claims = jwt.decode(data["assertion"], rsa_key.public_key(), algorithms=["RS256"], audience=TOKEN_URI)
    assert claims["iss"] == service_account["client_email"]
    assert claims["scope"] == "https://www.googleapis.com/auth/datastore"
    assert jwt.get_unverified_header(data["assertion"])["kid"] == "key1"
    assert aioclient_mock.mock_calls[1][3]["Authorization"] == "Bearer tok"


async def test_the_token_is_reused_until_it_is_about_to_expire(hass, aioclient_mock, service_account):
    now = [time.time()]
    aioclient_mock.post(TOKEN_URI, json=TOKEN_OK)
    aioclient_mock.get(LIVE, json={})
    client = make_client(hass, service_account, clock=lambda: now[0])

    await client.get_document(f"users/{UID}/live/car")
    now[0] += 3000
    await client.get_document(f"users/{UID}/live/car")
    assert sum(1 for call in aioclient_mock.mock_calls if call[0] == "POST") == 1

    now[0] += 600  # now within a minute of the hour
    await client.get_document(f"users/{UID}/live/car")
    assert sum(1 for call in aioclient_mock.mock_calls if call[0] == "POST") == 2


async def test_a_missing_document_is_none(hass, aioclient_mock, service_account):
    aioclient_mock.post(TOKEN_URI, json=TOKEN_OK)
    aioclient_mock.get(LIVE, status=404, json={"error": {"message": "not found"}})
    assert await make_client(hass, service_account).get_document(f"users/{UID}/live/car") is None


@pytest.mark.parametrize(
    ("status", "error"),
    [(403, FirestorePermissionError), (500, FirestoreError)],
)
async def test_refusals_are_told_apart(hass, aioclient_mock, service_account, status, error):
    aioclient_mock.post(TOKEN_URI, json=TOKEN_OK)
    aioclient_mock.get(LIVE, status=status, json={"error": {"message": "nope"}})
    with pytest.raises(error, match="nope"):
        await make_client(hass, service_account).get_document(f"users/{UID}/live/car")


async def test_a_query_error_arrives_as_a_list_and_its_reason_is_kept(hass, aioclient_mock, service_account):
    aioclient_mock.post(TOKEN_URI, json=TOKEN_OK)
    aioclient_mock.post(
        f"{DOCS}/users/{UID}:runQuery",
        status=400,
        json=[{"error": {"code": 400, "message": "The query requires an index."}}],
    )
    with pytest.raises(FirestoreError, match="requires an index"):
        await make_client(hass, service_account).run_query(f"users/{UID}", {"from": [{"collectionId": "trips"}]})


async def test_a_refused_token_is_renewed_once_and_then_it_is_an_auth_error(hass, aioclient_mock, service_account):
    aioclient_mock.post(TOKEN_URI, json=TOKEN_OK)
    aioclient_mock.get(LIVE, status=401, json={"error": {"message": "expired"}})
    with pytest.raises(FirestoreAuthError, match="expired"):
        await make_client(hass, service_account).get_document(f"users/{UID}/live/car")
    assert sum(1 for call in aioclient_mock.mock_calls if call[0] == "POST") == 2  # the second one was forced


async def test_google_refusing_the_key_is_an_auth_error(hass, aioclient_mock, service_account):
    aioclient_mock.post(TOKEN_URI, status=400, json={"error": "invalid_grant", "error_description": "Invalid JWT"})
    with pytest.raises(FirestoreAuthError, match="Invalid JWT"):
        await make_client(hass, service_account).get_document(f"users/{UID}/live/car")


async def test_a_key_that_cannot_sign_is_an_auth_error(hass, service_account):
    broken = {**service_account, "private_key": "not a key"}
    with pytest.raises(FirestoreAuthError):
        await make_client(hass, broken).get_document(f"users/{UID}/live/car")


async def test_no_network_is_a_connection_error(hass, aioclient_mock, service_account):
    aioclient_mock.post(TOKEN_URI, exc=aiohttp.ClientError("down"))
    with pytest.raises(FirestoreConnectionError):
        await make_client(hass, service_account).get_document(f"users/{UID}/live/car")


async def test_run_query_returns_the_documents_and_skips_the_empty_answer(hass, aioclient_mock, service_account):
    aioclient_mock.post(TOKEN_URI, json=TOKEN_OK)
    user = f"{DOCS}/users/{UID}:runQuery"
    aioclient_mock.post(
        user,
        json=[
            {"document": {"name": "x/trips/1000", **fields(distanceKm=2.5)}, "readTime": "t"},
            {"readTime": "t"},
        ],
    )
    query = {"from": [{"collectionId": "trips"}], "limit": 1}
    documents = await make_client(hass, service_account).run_query(f"users/{UID}", query)
    assert documents == [{"distanceKm": 2.5, "_id": "1000"}]
    assert aioclient_mock.mock_calls[-1][2] == {"structuredQuery": query}


async def test_documents_between_asks_for_the_id_range_and_only_the_fields_wanted(hass, aioclient_mock, service_account):
    aioclient_mock.post(TOKEN_URI, json=TOKEN_OK)
    aioclient_mock.post(
        f"{DOCS}/users/{UID}:runQuery",
        json=[
            {"document": {"name": "x/days/2026-09-21", **fields(distanceKm=10.5, trips=2)}, "readTime": "t"},
            {"document": {"name": "x/days/2026-09-22", **fields(distanceKm=5.0, trips=1)}, "readTime": "t"},
        ],
    )
    days = await make_client(hass, service_account).documents_between(
        f"users/{UID}", "days", "2026-09-21", "2026-09-27", ["distanceKm", "trips"]
    )

    assert days == [
        {"distanceKm": 10.5, "trips": 2, "_id": "2026-09-21"},
        {"distanceKm": 5.0, "trips": 1, "_id": "2026-09-22"},
    ]
    query = aioclient_mock.mock_calls[-1][2]["structuredQuery"]
    assert query["from"] == [{"collectionId": "days"}]
    assert query["select"] == {"fields": [{"fieldPath": "distanceKm"}, {"fieldPath": "trips"}]}
    first, last = query["where"]["compositeFilter"]["filters"]
    prefix = f"projects/{PROJECT}/databases/(default)/documents/users/{UID}/days/"
    assert first["fieldFilter"]["op"] == "GREATER_THAN_OR_EQUAL"
    assert first["fieldFilter"]["value"]["referenceValue"] == prefix + "2026-09-21"
    assert last["fieldFilter"]["op"] == "LESS_THAN_OR_EQUAL"
    assert last["fieldFilter"]["value"]["referenceValue"] == prefix + "2026-09-27"


async def test_documents_between_with_no_days_is_empty(hass, aioclient_mock, service_account):
    aioclient_mock.post(TOKEN_URI, json=TOKEN_OK)
    aioclient_mock.post(f"{DOCS}/users/{UID}:runQuery", json=[{"readTime": "t"}])
    assert await make_client(hass, service_account).documents_between(f"users/{UID}", "days", "a", "b", ["x"]) == []


async def test_an_html_error_page_is_an_error_not_a_crash(hass, aioclient_mock, service_account):
    aioclient_mock.post(TOKEN_URI, json=TOKEN_OK)
    aioclient_mock.get(LIVE, status=502, text="<html>Bad gateway</html>")
    with pytest.raises(FirestoreError, match="502"):
        await make_client(hass, service_account).get_document(f"users/{UID}/live/car")

    aioclient_mock.clear_requests()
    aioclient_mock.post(TOKEN_URI, status=503, text="<html>down</html>")
    with pytest.raises(FirestoreConnectionError):
        await make_client(hass, service_account).get_document(f"users/{UID}/live/car")
