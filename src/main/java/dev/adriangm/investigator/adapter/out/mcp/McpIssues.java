package dev.adriangm.investigator.adapter.out.mcp;

import dev.adriangm.investigator.adapter.support.Json;
import dev.adriangm.investigator.application.Ports.Issues;
import dev.adriangm.investigator.domain.Investigation.ApprovedIssue;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Creates approved GitHub issues and reconciles uncertain writes through a separate MCP session.
 */
public final class McpIssues implements Issues {
    private final StdioMcpTools.ProcessConfig process;

    /** Receives the independently configured write-and-reconciliation MCP process. */
    public McpIssues(StdioMcpTools.ProcessConfig process)
    {
        this.process = process;
    }

    @Override
    public String create(ApprovedIssue item)
    {
        try (var client = StdioMcpTools.connect(process)) {
            var definition = client
                .listTools()
                .tools()
                .stream()
                .filter(tool -> tool.name().equals("issue_write"))
                .findFirst()
                .orElseThrow();
            String[] repo = item.repository().split("/");
            Map<String, Object> args = Map
                .of("method", "create", "owner", repo[0], "repo", repo[1], "title", item.title(), "body", item.body());
            SchemaGuard.validate(definition.inputSchema(), args);
            var result = client.callTool(new CallToolRequest("issue_write", args));
            if (Boolean.TRUE.equals(result.isError())) {
                throw new IllegalStateException("GitHub write result is uncertain");
            }
            return urls(result, item.repository())
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("GitHub returned no issue receipt"));
        }
    }

    @Override
    public Optional<String> reconcile(ApprovedIssue item)
    {
        String marker = item.marker();
        String[] repo = item.repository().split("/");
        try (var client = StdioMcpTools.connect(process)) {
            String cursor = null;
            for (int page = 0; page < 20; page++) {
                Map<String, Object> args = new LinkedHashMap<>(
                    Map.of("owner", repo[0], "repo", repo[1], "perPage", 100)
                );
                if (cursor != null) {
                    args.put("after", cursor);
                }
                var result = client.callTool(new CallToolRequest("list_issues", args));
                if (Boolean.TRUE.equals(result.isError())) {
                    return Optional.empty();
                }
                var node = Json.MAPPER.readTree(payload(result));
                Optional<String> found = findMarker(node, marker, item.repository());
                if (found.isPresent()) {
                    return found;
                }
                String next = findCursor(node);
                if (next == null) {
                    return Optional.empty();
                }
                cursor = next;
            }
            return Optional.empty();
        }
    }

    private static Optional<String> findMarker(tools.jackson.databind.JsonNode node, String marker, String repository)
    {
        if (node.isObject() && node.path("body").asString("").contains(marker)) {
            for (String key : List.of("html_url", "url")) {
                String value = node.path(key).asString("");
                if (
                    value.matches("https://github\\.com/" + java.util.regex.Pattern.quote(repository) + "/issues/\\d+")
                ) {
                    return Optional.of(value);
                }
            }
        }
        if (node.isTextual()) {
            try {
                return findMarker(Json.MAPPER.readTree(node.asString()), marker, repository);
            } catch (RuntimeException ignored) {
                // A text node can contain ordinary prose rather than nested JSON.
            }
        }
        if ((node.isObject() || node.isArray())) {
            for (var child : node) {
                var found = findMarker(child, marker, repository);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    private static String findCursor(tools.jackson.databind.JsonNode node)
    {
        if (node.isObject() && node.path("pageInfo").path("hasNextPage").asBoolean(false)) {
            return node.path("pageInfo").path("endCursor").asString();
        }
        if (node.isTextual()) {
            try {
                return findCursor(Json.MAPPER.readTree(node.asString()));
            } catch (RuntimeException ignored) {
                // A text node can contain ordinary prose rather than nested JSON.
            }
        }
        if ((node.isObject() || node.isArray())) {
            for (var child : node) {
                String found = findCursor(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static String payload(CallToolResult result)
    {
        List<Object> parts = new ArrayList<>();
        if (result.structuredContent() != null) {
            parts.add(result.structuredContent());
        }
        for (var content : result.content()) {
            if (content instanceof TextContent text) {
                parts.add(text.text());
            }
        }
        return Json.encode(parts);
    }

    private static List<String> urls(CallToolResult result, String repository)
    {
        var matcher = java.util.regex.Pattern
            .compile("https://github\\.com/" + java.util.regex.Pattern.quote(repository) + "/issues/\\d+")
            .matcher(payload(result));
        List<String> urls = new ArrayList<>();
        while (matcher.find()) {
            urls.add(matcher.group());
        }
        return urls;
    }
}
