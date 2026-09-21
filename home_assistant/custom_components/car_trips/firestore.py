"""A small read-only Firestore client for a service account, over REST.

Only what the integration needs: read one document, run a query, and read a range of documents.
It signs its own token request with PyJWT, which Home Assistant already ships, so nothing extra is installed.
"""

from __future__ import annotations

import asyncio
import json
import time
from collections.abc import Callable
from typing import Any

import aiohttp
import jwt

API = "https://firestore.googleapis.com/v1"
SCOPE = "https://www.googleapis.com/auth/datastore"
DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token"

# tools/mock_firestore.py stands in for Google with a made-up car. Its key file names this project and an
# "api_endpoint"; the endpoint is honoured for this project only, so a real key can never be sent somewhere else.
MOCK_PROJECT_ID = "car-trips-mock"

# A new token is asked for this long before the old one runs out.
TOKEN_MARGIN_SECONDS = 60
REQUEST_TIMEOUT = aiohttp.ClientTimeout(total=20)


class FirestoreError(Exception):
    """Anything that stops a read."""


class FirestoreConnectionError(FirestoreError):
    """Google could not be reached."""


class FirestoreAuthError(FirestoreError):
    """The service account is not accepted (wrong, deleted or its key revoked)."""


class FirestorePermissionError(FirestoreError):
    """The service account is fine but is not allowed to read Firestore."""


class InvalidServiceAccount(ValueError):
    """The text is not a service account key file."""


def parse_service_account(text: str) -> dict[str, Any]:
    """The key file Google gives out, checked for what is needed to use it."""
    try:
        data = json.loads(text)
    except (TypeError, ValueError) as err:
        raise InvalidServiceAccount("not JSON") from err
    if not isinstance(data, dict) or data.get("type") != "service_account":
        raise InvalidServiceAccount("not a service account key")
    for key in ("client_email", "private_key", "project_id"):
        if not isinstance(data.get(key), str) or not data[key]:
            raise InvalidServiceAccount(f"missing {key}")
    return data


def decode_value(value: dict[str, Any]) -> Any:
    """A Firestore REST value ({"integerValue": "3"}) as a plain Python value."""
    if "nullValue" in value:
        return None
    if "booleanValue" in value:
        return value["booleanValue"]
    if "integerValue" in value:
        return int(value["integerValue"])
    if "doubleValue" in value:
        return float(value["doubleValue"])
    if "mapValue" in value:
        return decode_fields(value["mapValue"].get("fields", {}))
    if "arrayValue" in value:
        return [decode_value(item) for item in value["arrayValue"].get("values", [])]
    if "geoPointValue" in value:
        return value["geoPointValue"]
    for key in ("stringValue", "timestampValue", "referenceValue", "bytesValue"):
        if key in value:
            return value[key]
    return None


def decode_fields(fields: dict[str, Any]) -> dict[str, Any]:
    return {name: decode_value(value) for name, value in fields.items()}


def decode_document(document: dict[str, Any]) -> dict[str, Any]:
    """The fields of a document, with its id under "_id"."""
    decoded = decode_fields(document.get("fields", {}))
    decoded["_id"] = document.get("name", "").rsplit("/", 1)[-1]
    return decoded


class FirestoreClient:
    """Reads the default Firestore database of the project the service account belongs to."""

    def __init__(
        self,
        session: aiohttp.ClientSession,
        service_account: dict[str, Any],
        *,
        clock: Callable[[], float] = time.time,
    ) -> None:
        self._session = session
        self._account = service_account
        self._clock = clock
        self._token: str | None = None
        self._token_expires = 0.0
        self._token_lock = asyncio.Lock()

    @property
    def project_id(self) -> str:
        return self._account["project_id"]

    @property
    def _api(self) -> str:
        if self.project_id == MOCK_PROJECT_ID and self._account.get("api_endpoint"):
            return str(self._account["api_endpoint"]).rstrip("/")
        return API

    @property
    def _root(self) -> str:
        return f"projects/{self.project_id}/databases/(default)/documents"

    def document_name(self, path: str) -> str:
        """The full name of a document, as filters refer to it."""
        return f"{self._root}/{path}"

    # -- token ---------------------------------------------------------------------------------------------

    def _assertion(self, now: float) -> str:
        token_uri = self._account.get("token_uri", DEFAULT_TOKEN_URI)
        claims = {
            "iss": self._account["client_email"],
            "scope": SCOPE,
            "aud": token_uri,
            "iat": int(now),
            "exp": int(now) + 3600,
        }
        headers = {"kid": self._account["private_key_id"]} if self._account.get("private_key_id") else None
        return jwt.encode(claims, self._account["private_key"], algorithm="RS256", headers=headers)

    async def _access_token(self, *, force: bool = False) -> str:
        async with self._token_lock:
            now = self._clock()
            if not force and self._token and now < self._token_expires - TOKEN_MARGIN_SECONDS:
                return self._token
            try:
                # Signing is a little CPU work and the first use imports the crypto library: not on the loop.
                assertion = await asyncio.to_thread(self._assertion, now)
            except (ValueError, TypeError, jwt.PyJWTError) as err:
                raise FirestoreAuthError(f"The private key cannot sign: {err}") from err
            token_uri = self._account.get("token_uri", DEFAULT_TOKEN_URI)
            try:
                async with self._session.post(
                    token_uri,
                    data={"grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer", "assertion": assertion},
                    timeout=REQUEST_TIMEOUT,
                ) as response:
                    body = await _json(response)
                    status = response.status
            except (aiohttp.ClientError, TimeoutError) as err:
                raise FirestoreConnectionError(str(err) or type(err).__name__) from err
            if status in (400, 401, 403):
                raise FirestoreAuthError(_message(body) or f"token request refused ({status})")
            if status != 200 or not isinstance(body, dict) or "access_token" not in body:
                raise FirestoreConnectionError(_message(body) or f"token request failed ({status})")
            self._token = body["access_token"]
            self._token_expires = now + float(body.get("expires_in", 3600))
            return self._token

    # -- requests ------------------------------------------------------------------------------------------

    async def _request(self, method: str, path: str, body: dict[str, Any] | None = None) -> Any:
        """The JSON answer, or None when the document is not there. The token is renewed once if it is refused."""
        for attempt in (0, 1):
            token = await self._access_token(force=attempt == 1)
            try:
                async with self._session.request(
                    method,
                    f"{self._api}/{path}",
                    json=body,
                    headers={"Authorization": f"Bearer {token}"},
                    timeout=REQUEST_TIMEOUT,
                ) as response:
                    status = response.status
                    payload = await _json(response)
            except (aiohttp.ClientError, TimeoutError) as err:
                raise FirestoreConnectionError(str(err) or type(err).__name__) from err

            if status == 200:
                return payload
            if status == 404:
                return None
            if status == 401 and attempt == 0:
                continue
            if status in (401, 403):
                error = _message(payload)
                if status == 401:
                    raise FirestoreAuthError(error or "not authorised")
                raise FirestorePermissionError(error or "permission denied")
            raise FirestoreError(_message(payload) or f"Firestore answered {status}")
        raise FirestoreAuthError("not authorised")  # pragma: no cover

    async def get_document(self, path: str) -> dict[str, Any] | None:
        """One document as a dict (with "_id"), or None if it does not exist."""
        payload = await self._request("GET", f"{self._root}/{path}")
        return decode_document(payload) if payload else None

    async def run_query(self, parent_path: str, structured_query: dict[str, Any]) -> list[dict[str, Any]]:
        """The documents a query finds under [parent_path], each as a dict with "_id"."""
        payload = await self._request("POST", f"{self._root}/{parent_path}:runQuery", {"structuredQuery": structured_query})
        return [decode_document(item["document"]) for item in payload or [] if "document" in item]

    async def documents_between(
        self,
        parent_path: str,
        collection: str,
        first_id: str,
        last_id: str,
        fields: list[str],
    ) -> list[dict[str, Any]]:
        """The documents of [collection] whose ids run from first to last, each keeping only [fields].

        Firestore cannot add fields up over a range of ids without an index it does not support, so the caller adds
        them up. Filtering on the id alone needs no index.
        """
        parent = f"{self._root}/{parent_path}"
        return await self.run_query(
            parent_path,
            {
                "from": [{"collectionId": collection}],
                "select": {"fields": [{"fieldPath": name} for name in fields]},
                "where": {
                    "compositeFilter": {
                        "op": "AND",
                        "filters": [
                            _id_filter("GREATER_THAN_OR_EQUAL", f"{parent}/{collection}/{first_id}"),
                            _id_filter("LESS_THAN_OR_EQUAL", f"{parent}/{collection}/{last_id}"),
                        ],
                    }
                },
            },
        )


async def _json(response: aiohttp.ClientResponse) -> Any:
    """The JSON body, or None when there is none: a proxy or an outage answers with a page of HTML."""
    try:
        return await response.json(content_type=None)
    except ValueError:
        return None


def _id_filter(op: str, name: str) -> dict[str, Any]:
    return {"fieldFilter": {"field": {"fieldPath": "__name__"}, "op": op, "value": {"referenceValue": name}}}


def _message(payload: Any) -> str:
    """What Google says went wrong, from either of its two error shapes."""
    if isinstance(payload, list):
        # Query endpoints stream their answer, so an error arrives as the one item of a list.
        payload = next((item for item in payload if isinstance(item, dict) and "error" in item), None)
    if not isinstance(payload, dict):
        return ""
    error = payload.get("error")
    if isinstance(error, dict):
        return str(error.get("message", ""))
    description = payload.get("error_description")
    return str(description or error or "")
