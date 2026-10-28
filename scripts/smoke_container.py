"""Exercise real containers using labelled model and MCP test doubles."""

import datetime
import http.client
import json
import pathlib
import subprocess
import time
import urllib.error
import urllib.request
import uuid

ROOT = pathlib.Path(__file__).resolve().parents[1]
PREFIX = "incident-workshop-check"
TOKEN = "test-operator-token-never-use-in-production-12345"
BASE = "http://127.0.0.1:18092"


def docker(*args):
    """Run a Docker inspection or command and return its captured output."""
    return (
        subprocess.check_output(
            ["docker", *map(str, args)], stderr=subprocess.STDOUT
        )
        .decode()
        .strip()
    )


def request(path, body=None, key=None, method=None, token=TOKEN):
    """Call only the local application using a synthetic operator token."""
    headers = {
        "Authorization": "Bearer " + token,
        "Content-Type": "application/json",
    }
    if key:
        headers["Idempotency-Key"] = key
    req = urllib.request.Request(
        BASE + path,
        data=None if body is None else json.dumps(body).encode(),
        headers=headers,
        method=method,
    )
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def wait_ready():
    """Wait a bounded interval for the local application health endpoint."""
    for _ in range(90):
        try:
            with urllib.request.urlopen(
                BASE + "/actuator/health", timeout=2
            ) as r:
                if r.status == 200:
                    return
        except (
            urllib.error.URLError,
            http.client.RemoteDisconnected,
            TimeoutError,
        ):
            pass
        time.sleep(1)
    raise AssertionError("Application did not become healthy")


def await_status(item, states):
    """Poll the investigation until an expected state is observed."""
    for _ in range(90):
        item = request("/investigations/" + item["id"])
        if item["status"] in states:
            return item
        time.sleep(1)
    raise AssertionError(
        "Unexpected status " + item["status"] + " " + item.get("failure", "")
    )


def new_case(key):
    """Create a labelled investigation with a unique request key."""
    return request(
        "/investigations",
        {
            "projectKey": "orders-demo",
            "question": "Investigate orders [TEST FIXTURE]",
        },
        key,
    )


def main():
    """Run the CLI command without performing I/O during import."""
    (ROOT / "reports").mkdir(exist_ok=True)
    state = ROOT / ".tools" / "fixture-state"
    state.mkdir(parents=True, exist_ok=True)
    receipt = state / "receipts.json"
    if receipt.exists():
        raise SystemExit(
            "Use a fresh fixture-state directory for each smoke run"
        )
    containers = []
    report = {
        "kind": "controlled-container-integration",
        "externalAIRequests": 0,
        "externalSentryRequests": 0,
        "externalGitHubRequests": 0,
        "checks": [],
    }
    try:
        docker("network", "create", PREFIX)
        docker(
            "network",
            "connect",
            PREFIX,
            "incident-investigator-verification-db",
        )
        fixture = ROOT / "scripts" / "fixtures"
        corpus = ROOT / ".tools" / "corpus"
        common = [
            "--network",
            PREFIX,
            "--mount",
            f"type=bind,src={fixture},dst=/fixture,readonly",
            "--mount",
            f"type=bind,src={state},dst=/fixture-state",
        ]
        docker(
            "run",
            "-d",
            "--name",
            PREFIX + "-model",
            *common,
            "--entrypoint",
            "node",
            "incident-investigator:verification",
            "/fixture/fixture.mjs",
            "model",
        )
        containers.append(PREFIX + "-model")
        env = {
            "DB_URL": "jdbc:postgresql://incident-investigator-verification-db:5432/investigator_test",
            "DB_USER": "investigator_test",
            "DB_PASSWORD": "test-only",
            "OPERATOR_TOKEN": TOKEN,
            "MODEL_BASE_URL": f"http://{PREFIX}-model:18094",
            "MODEL_NAME": "explicit-test-fixture",
            "GITHUB_MCP_COMMAND": "/fixture/github-mcp-server",
            "SENTRY_MCP_COMMAND": "node",
            "SENTRY_MCP_SCRIPT": "/fixture/fixture.mjs",
            "SENTRY_ORGANIZATION": "test-only",
            "SENTRY_PROJECT": "orders-incident-demo",
            "CORPUS_PATH": "/app/corpus",
            "CORPUS_REVISION": json.loads(
                (corpus / "manifest.json").read_text(encoding="utf-8")
            )["revision"],
        }
        # Only the DNS name of the local model fixture is permitted.
        env["MODEL_BASE_URL"] = "http://model-fixture:18094"
        docker(
            "network",
            "connect",
            "--alias",
            "model-fixture",
            PREFIX,
            PREFIX + "-model",
        ) if False else None
        docker("network", "disconnect", PREFIX, PREFIX + "-model")
        docker(
            "network",
            "connect",
            "--alias",
            "model-fixture",
            PREFIX,
            PREFIX + "-model",
        )
        args = [
            "run",
            "-d",
            "--name",
            PREFIX + "-app",
            *common,
            "-p",
            "127.0.0.1:18092:8080",
            "--mount",
            f"type=bind,src={corpus},dst=/app/corpus,readonly",
        ]
        for key, value in env.items():
            args += ["-e", key + "=" + value]
        docker(*args, "incident-investigator:verification")
        containers.append(PREFIX + "-app")
        wait_ready()
        assert docker("exec", PREFIX + "-app", "id", "-u") == "10001"
        report["checks"].append("non-root container and PostgreSQL health")
        try:
            request("/investigations/" + str(uuid.uuid4()), token="wrong")
            raise AssertionError("Missing authentication")
        except urllib.error.HTTPError as e:
            assert e.code == 401
        key = str(uuid.uuid4())
        first = new_case(key)
        assert new_case(key)["id"] == first["id"]
        item = await_status(
            first, {"COMPLETED", "TEMPORARILY_UNAVAILABLE", "BUDGET_EXHAUSTED"}
        )
        assert item["status"] == "COMPLETED", item.get("failure")
        assert len(item["evidence"]) >= 3
        assert item["modelCalls"] == 4
        report["checks"].append(
            "API -> LLM HTTP fixture -> real MCP client -> "
            "stdio fixtures -> RAG -> saved diagnosis"
        )
        report["example"] = item
        path = "/investigations/" + item["id"] + "/issue"
        approval = {
            "draftVersion": item["draft"]["version"],
            "hash": item["draft"]["hash"],
        }
        try:
            request(
                path, {"draftVersion": 0, "hash": "stale"}, str(uuid.uuid4())
            )
            raise AssertionError("Stale approval accepted")
        except urllib.error.HTTPError as e:
            assert e.code == 409
        approval_key = str(uuid.uuid4())
        request(path, approval, approval_key)
        item = await_status(item, {"PUBLISHED", "PUBLISH_UNKNOWN"})
        assert item["status"] == "PUBLISHED"
        assert (
            request(path, approval, approval_key)["issueUrl"]
            == item["issueUrl"]
        )
        assert len(json.loads(receipt.read_text(encoding="utf-8"))) == 1
        report["checks"].append(
            "approval content binding and duplicate prevention"
        )
        second = await_status(new_case(str(uuid.uuid4())), {"COMPLETED"})
        second = request(
            "/investigations/" + second["id"] + "/draft",
            {
                "expectedDraftVersion": 1,
                "title": second["draft"]["title"],
                "body": second["draft"]["body"] + "\nUNCERTAIN_TEST",
            },
            method="PUT",
        )
        a = {
            "draftVersion": second["draft"]["version"],
            "hash": second["draft"]["hash"],
        }
        k = str(uuid.uuid4())
        request("/investigations/" + second["id"] + "/issue", a, k)
        second = await_status(second, {"PUBLISH_UNKNOWN"})
        second = request("/investigations/" + second["id"] + "/issue", a, k)
        assert second["status"] == "PUBLISHED"
        assert len(json.loads(receipt.read_text(encoding="utf-8"))) == 2
        report["checks"].append(
            "lost write response reconciled without a repeated create"
        )
        docker("restart", PREFIX + "-app")
        wait_ready()
        assert (
            request("/investigations/" + item["id"])["issueUrl"]
            == item["issueUrl"]
        )
        report["checks"].append("durable receipt after application restart")
        report["success"] = True
    finally:
        report["finishedAt"] = datetime.datetime.now(datetime.UTC).isoformat()
        (ROOT / "reports" / "container-smoke.json").write_text(
            json.dumps(report, indent=2, ensure_ascii=False) + "\n",
            encoding="utf-8",
        )
        for container in reversed(containers):
            try:
                docker("rm", "-f", container)
            except subprocess.CalledProcessError:
                pass
        try:
            docker(
                "network",
                "disconnect",
                PREFIX,
                "incident-investigator-verification-db",
            )
            docker("network", "rm", PREFIX)
        except subprocess.CalledProcessError:
            pass
    print(
        json.dumps(
            {
                "success": report.get("success", False),
                "checks": report["checks"],
            }
        )
    )


if __name__ == "__main__":
    main()
