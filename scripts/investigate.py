"""Submit a real investigation; never approves or creates an issue."""

import argparse
import datetime
import json
import os
import pathlib
import time
import urllib.request
import uuid


def request(base, token, path, body=None, key=None):
    """Call the API with the operator token only in its header."""
    headers = {
        "Authorization": "Bearer " + token,
        "Content-Type": "application/json",
    }
    if key:
        headers["Idempotency-Key"] = key
    req = urllib.request.Request(
        base + path,
        data=None if body is None else json.dumps(body).encode(),
        headers=headers,
    )
    with urllib.request.urlopen(req, timeout=30) as response:
        return json.load(response)


def run(base, token, question):
    """Submit and poll an investigation without approving an issue."""
    until = datetime.datetime.now(datetime.UTC)
    item = request(
        base,
        token,
        "/investigations",
        {
            "projectKey": "orders-demo",
            "question": question,
            "since": (until - datetime.timedelta(days=1)).isoformat(),
            "until": until.isoformat(),
        },
        str(uuid.uuid4()),
    )
    deadline = time.monotonic() + 420
    while (
        item["status"] in ("QUEUED", "RUNNING") and time.monotonic() < deadline
    ):
        time.sleep(1)
        item = request(base, token, "/investigations/" + item["id"])
    return item


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "question",
        nargs="?",
        default=(
            "Algunos pedidos fallan al guardarse. "
            "Investiga qué entradas están afectadas, "
            "contrasta las trazas con el código y la documentación "
            "y prepara una incidencia con evidencias."
        ),
    )
    parser.add_argument("--output", default="reports/investigation.json")
    args = parser.parse_args()
    item = run(
        os.getenv("INVESTIGATOR_URL", "http://127.0.0.1:18092"),
        os.environ["OPERATOR_TOKEN"],
        args.question,
    )
    path = pathlib.Path(args.output)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(item, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    print(
        json.dumps(
            {
                "id": item["id"],
                "status": item["status"],
                "evidence": len(item["evidence"]),
                "output": str(path),
            },
            ensure_ascii=False,
        )
    )
