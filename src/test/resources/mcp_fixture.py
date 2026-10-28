"""Serve explicit JSON-RPC fixtures without accessing external services."""

import json
import sys


def schema(props, required):
    """Build a closed argument schema for the controlled MCP tools."""
    return {
        "type": "object",
        "properties": {key: {"type": "string"} for key in props},
        "required": required,
        "additionalProperties": False,
    }


def main():
    """Run the CLI command without performing I/O during import."""
    server = sys.argv[1]
    github = [
        {
            "name": "get_commit",
            "description": "Read a commit [TEST FIXTURE]",
            "inputSchema": schema(
                ["owner", "repo", "sha"], ["owner", "repo", "sha"]
            ),
        }
    ]
    catalog = [
        {
            "name": "search_issues",
            "description": "Search issues [TEST FIXTURE]",
            "inputSchema": schema(
                ["organizationSlug", "projectSlugOrId", "regionUrl", "query"],
                ["organizationSlug"],
            ),
        }
    ]
    sentry = [
        {
            "name": "search_sentry_tools",
            "description": "Discover fixture catalog",
            "inputSchema": {"type": "object"},
        },
        {
            "name": "execute_sentry_tool",
            "description": "Execute fixture catalog",
            "inputSchema": {"type": "object"},
        },
    ]
    for line in sys.stdin:
        try:
            request = json.loads(line)
            if "id" not in request:
                continue
            method = request["method"]
            params = request.get("params", {})
            if method == "initialize":
                result = {
                    "protocolVersion": request["params"]["protocolVersion"],
                    "capabilities": {"tools": {}},
                    "serverInfo": {
                        "name": "explicit-test-fixture",
                        "version": "1.0",
                    },
                }
            elif method == "tools/list":
                result = {"tools": github if server == "github" else sentry}
            elif method == "tools/call":
                if params["name"] == "search_sentry_tools":
                    result = {
                        "content": [
                            {
                                "type": "text",
                                "text": json.dumps({"tools": catalog}),
                            }
                        ],
                        "structuredContent": {"tools": catalog},
                        "isError": False,
                    }
                else:
                    result = {
                        "content": [
                            {
                                "type": "text",
                                "text": json.dumps(
                                    {
                                        "kind": "TEST FIXTURE",
                                        "received": params,
                                        "url": "https://github.com/example/orders-demo/commit/abc",
                                    }
                                ),
                            }
                        ],
                        "isError": False,
                    }
            elif method == "ping":
                result = {}
            else:
                result = {}
            print(
                json.dumps(
                    {"jsonrpc": "2.0", "id": request["id"], "result": result}
                ),
                flush=True,
            )
        except Exception:
            print(
                json.dumps(
                    {
                        "jsonrpc": "2.0",
                        "id": request.get("id"),
                        "error": {"code": -32603, "message": "Fixture error"},
                    }
                ),
                flush=True,
            )


if __name__ == "__main__":
    main()
