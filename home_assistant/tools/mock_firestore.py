"""A stand-in for Google's Firestore and token servers, serving the sample car.

Run it on any computer Home Assistant can reach, then add the integration with the key file it writes:

    python mock_firestore.py --scenario driving

It speaks just the requests the integration makes (read a document, run a query, add up a field over a range of
ids) in the same JSON Google uses, and refuses anything else with an error rather than guessing.
"""

from __future__ import annotations

import argparse
import json
import socket
import sys
from collections.abc import Callable
from pathlib import Path
from typing import Any

from aiohttp import web

sys.path.insert(0, str(Path(__file__).parent))
from sample_data import SampleCar  # noqa: E402

MOCK_PROJECT = "car-trips-mock"
TOKEN = "mock-token"


def encode(value: Any) -> dict[str, Any]:
    """A Python value as a Firestore REST value."""
    if value is None:
        return {"nullValue": None}
    if isinstance(value, bool):
        return {"booleanValue": value}
    if isinstance(value, int):
        return {"integerValue": str(value)}
    if isinstance(value, float):
        return {"doubleValue": value}
    if isinstance(value, str):
        return {"stringValue": value}
    if isinstance(value, dict):
        return {"mapValue": {"fields": {k: encode(v) for k, v in value.items()}}}
    raise TypeError(f"cannot encode {value!r}")


def document(path: str, fields: dict[str, Any]) -> dict[str, Any]:
    return {
        "name": f"projects/{MOCK_PROJECT}/databases/(default)/documents/{path}",
        "fields": {k: encode(v) for k, v in fields.items()},
    }


def _error(status: int, message: str) -> web.Response:
    return web.json_response({"error": {"code": status, "message": message}}, status=status)


def _children(documents: dict[str, dict[str, Any]], parent: str, collection: str) -> dict[str, dict[str, Any]]:
    prefix = f"{parent}/{collection}/"
    return {path: fields for path, fields in documents.items() if path.startswith(prefix) and "/" not in path[len(prefix) :]}


def _run_query(documents: dict[str, dict[str, Any]], parent: str, query: dict[str, Any]) -> web.Response:
    unsupported = set(query) - {"from", "orderBy", "limit", "where", "select"}
    if unsupported:
        return _error(400, f"mock does not support {sorted(unsupported)} in a query")
    collection = query["from"][0]["collectionId"]
    root = f"projects/{MOCK_PROJECT}/databases/(default)/documents/"
    low, high = "", "￿"
    for filter_ in query.get("where", {}).get("compositeFilter", {}).get("filters", []):
        field = filter_["fieldFilter"]
        if field["field"]["fieldPath"] != "__name__":
            return _error(400, "mock only filters on __name__")
        name = field["value"]["referenceValue"].removeprefix(root)
        if field["op"] == "GREATER_THAN_OR_EQUAL":
            low = name
        elif field["op"] == "LESS_THAN_OR_EQUAL":
            high = name
        else:
            return _error(400, f"mock does not support {field['op']}")
    found = [(path, fields) for path, fields in _children(documents, parent, collection).items() if low <= path <= high]
    for order in reversed(query.get("orderBy", [])):
        field = order["field"]["fieldPath"]
        found.sort(key=lambda item: item[1].get(field, 0), reverse=order.get("direction") == "DESCENDING")
    if "limit" in query:
        found = found[: query["limit"]]
    if "select" in query:
        wanted = {item["fieldPath"] for item in query["select"]["fields"]}
        found = [(path, {k: v for k, v in fields.items() if k in wanted}) for path, fields in found]
    answer = [{"document": document(path, fields), "readTime": "2026-01-01T00:00:00Z"} for path, fields in found]
    return web.json_response(answer or [{"readTime": "2026-01-01T00:00:00Z"}])


def make_app(car: SampleCar, *, log: Callable[[str], None] = lambda line: None) -> web.Application:
    async def token(request: web.Request) -> web.Response:
        form = await request.post()
        if form.get("grant_type") != "urn:ietf:params:oauth:grant-type:jwt-bearer" or not form.get("assertion"):
            return web.json_response({"error": "invalid_request"}, status=400)
        log("token issued")
        return web.json_response({"access_token": TOKEN, "expires_in": 3600, "token_type": "Bearer"})

    async def get_document(request: web.Request) -> web.Response:
        if request.headers.get("Authorization") != f"Bearer {TOKEN}":
            return _error(401, "mock: bad token")
        path = request.match_info["tail"]
        found = car.documents().get(path)
        log(f"GET {path}: {'found' if found is not None else 'missing'}")
        return web.json_response(document(path, found)) if found is not None else _error(404, "Document not found")

    async def post(request: web.Request) -> web.Response:
        if request.headers.get("Authorization") != f"Bearer {TOKEN}":
            return _error(401, "mock: bad token")
        tail = request.match_info["tail"]
        parent, _, method = tail.rpartition(":")
        body = await request.json()
        documents = car.documents()
        log(f"POST {tail}")
        if method == "runQuery":
            return _run_query(documents, parent, body["structuredQuery"])
        return _error(400, f"mock does not know {method}")

    docs = f"/v1/projects/{{project}}/databases/(default)/documents/{{tail:.+}}"
    app = web.Application()
    app.router.add_post("/token", token)
    app.router.add_get(docs, get_document)
    app.router.add_post(docs, post)
    return app


def service_account(base_url: str) -> dict[str, Any]:
    """A key file that makes the integration talk to the mock at [base_url]. Its private key is a throwaway."""
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import rsa

    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    pem = key.private_bytes(
        serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()
    ).decode()
    return {
        "type": "service_account",
        "project_id": MOCK_PROJECT,
        "private_key_id": "mock",
        "private_key": pem,
        "client_email": f"mock@{MOCK_PROJECT}.iam.gserviceaccount.com",
        "token_uri": f"{base_url}/token",
        # Honoured by the integration only for this project id, so a real key cannot be pointed elsewhere.
        "api_endpoint": f"{base_url}/v1",
    }


def _lan_address() -> str:
    """The address of this computer on the network, as far as it can be told without asking anyone."""
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
        try:
            probe.connect(("10.255.255.255", 1))
            return probe.getsockname()[0]
        except OSError:
            return "127.0.0.1"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--scenario", choices=["parked", "driving"], default="driving")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--host", default="0.0.0.0", help="the address to listen on")
    parser.add_argument("--public-host", help="the address Home Assistant uses to reach this computer (default: its LAN address)")
    parser.add_argument("--uid", default="sample-car", help="the Firebase user id to type into Home Assistant")
    parser.add_argument("--utc-offset", type=float, default=7, help="hours from UTC of the car's time zone (Home Assistant's must match)")
    parser.add_argument("--key-file", default="mock_service_account.json")
    args = parser.parse_args()

    public = args.public_host or _lan_address()
    base_url = f"http://{public}:{args.port}"
    Path(args.key_file).write_text(json.dumps(service_account(base_url), indent=2), encoding="utf-8")

    car = SampleCar(args.scenario, uid=args.uid, utc_offset_hours=args.utc_offset)
    print(f"Sample car '{args.scenario}' ({len(car.documents())} documents) at {base_url}")
    print(f"Key file written to {Path(args.key_file).resolve()}")
    print(f"In Home Assistant add Car Trips, paste that file's content, and use the user ID: {args.uid}")
    print("Ctrl+C to stop.")
    web.run_app(make_app(car, log=print), host=args.host, port=args.port, print=None)


if __name__ == "__main__":
    main()
