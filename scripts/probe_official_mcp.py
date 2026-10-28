"""Inspect official MCP catalogs offline with nonfunctional credentials."""

import json
import os
import pathlib
import queue
import subprocess
import threading
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]


def probe(command, env):
    """Initialize one stdio server and return its discovered tool catalog."""
    proc = subprocess.Popen(
        command,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        text=True,
        encoding="utf-8",
        env={**os.environ, **env},
        bufsize=1,
    )
    output = queue.Queue()

    def _read_stdout():
        for line in proc.stdout:
            try:
                output.put(json.loads(line))
            except ValueError:
                pass

    reader = threading.Thread(target=_read_stdout, daemon=True)
    reader.start()

    def _call(method, params, request_id):
        proc.stdin.write(
            json.dumps(
                {
                    "jsonrpc": "2.0",
                    "id": request_id,
                    "method": method,
                    "params": params,
                }
            )
            + "\n"
        )
        proc.stdin.flush()
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            result = output.get(timeout=max(0.1, deadline - time.monotonic()))
            if result.get("id") == request_id:
                if "error" in result:
                    raise RuntimeError(result["error"])
                return result["result"]
        raise TimeoutError(method)

    try:
        init = _call(
            "initialize",
            {
                "protocolVersion": "2025-11-25",
                "capabilities": {},
                "clientInfo": {"name": "capability-probe", "version": "1.0"},
            },
            1,
        )
        proc.stdin.write(
            json.dumps(
                {"jsonrpc": "2.0", "method": "notifications/initialized"}
            )
            + "\n",
        )
        proc.stdin.flush()
        tools = _call("tools/list", {}, 2)
        result = {
            "server": {
                k: v for k, v in init["serverInfo"].items() if k != "icons"
            },
            "toolNames": [t["name"] for t in tools["tools"]],
        }
        if "search_sentry_tools" in result["toolNames"]:
            result["catalog"] = _call(
                "tools/call",
                {
                    "name": "search_sentry_tools",
                    "arguments": {
                        "query": (
                            "search_issue_events get_issue_details "
                            "get_release_details find_releases "
                            "get_trace_details"
                        ),
                        "limit": 20,
                    },
                },
                3,
            )
        return result
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait()
        reader.join(timeout=5)
        proc.stdin.close()
        proc.stdout.close()


if __name__ == "__main__":
    (ROOT / "reports").mkdir(exist_ok=True)
    result = {
        "kind": "official-binary-capability-probe",
        "serviceDataQueried": False,
    }
    result["sentry"] = probe(
        [
            "docker",
            "run",
            "--rm",
            "-i",
            "--network",
            "none",
            "-e",
            "SENTRY_ACCESS_TOKEN=nonfunctional-capability-probe",
            "-e",
            "SENTRY_HOST=example.invalid",
            "--entrypoint",
            "node",
            "incident-investigator:verification",
            "/opt/sentry/node_modules/@sentry/mcp-server/dist/index.js",
            "--skills=inspect",
            "--sentry-dsn=",
        ],
        {},
    )
    result["github"] = probe(
        [
            "docker",
            "run",
            "--rm",
            "-i",
            "--network",
            "none",
            "-e",
            "GITHUB_PERSONAL_ACCESS_TOKEN=nonfunctional-capability-probe",
            "--entrypoint",
            "github-mcp-server",
            "incident-investigator:verification",
            "stdio",
            "--read-only",
            "--toolsets=repos,pull_requests,issues",
        ],
        {},
    )
    (ROOT / "reports" / "official-capabilities.json").write_text(
        json.dumps(result, indent=2) + "\n", encoding="utf-8"
    )
    print(
        json.dumps(
            {
                name: len(value["toolNames"])
                for name, value in result.items()
                if isinstance(value, dict)
            }
        )
    )
