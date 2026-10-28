package dev.adriangm.investigator.adapter.out.mcp;

import dev.adriangm.investigator.adapter.support.Json;
import dev.adriangm.investigator.adapter.support.Redactor;
import dev.adriangm.investigator.application.AgentFailure;
import dev.adriangm.investigator.application.Ports.Tool;
import dev.adriangm.investigator.application.Ports.ToolSession;
import dev.adriangm.investigator.application.Ports.Tools;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Adapts configured stdio MCP servers to the application's scoped read-only tool port. */
public final class StdioMcpTools implements Tools {
  /** Executable, arguments and environment for one independently configured MCP server process. */
  public record ProcessConfig(
      String command, List<String> arguments, Map<String, String> environment) {}

  private final Map<String, ProcessConfig> processes;
  private final ToolPolicy policy;
  private final Redactor redactor;

  /** Receives server processes, scope policy and sanitization for recorded tool results. */
  public StdioMcpTools(Map<String, ProcessConfig> processes, ToolPolicy policy, Redactor redactor) {
    this.processes = Map.copyOf(processes);
    this.policy = policy;
    this.redactor = redactor;
  }

  /** Starts and initializes one configured MCP client with bounded request waits. */
  public static McpSyncClient connect(ProcessConfig config) {
    var params =
        ServerParameters.builder(config.command())
            .args(config.arguments())
            .env(config.environment())
            .build();
    var transport = new StdioClientTransport(params, new JacksonMcpJsonMapper(Json.MAPPER));
    // Never log stderr: upstream processes may print token-bearing diagnostics.
    transport.setStdErrorHandler(line -> {});
    var client =
        McpClient.sync(transport)
            .initializationTimeout(Duration.ofSeconds(20))
            .requestTimeout(Duration.ofSeconds(20))
            .build();
    try {
      client.initialize();
      return client;
    } catch (RuntimeException e) {
      client.close();
      throw new AgentFailure("MCP_INITIALIZATION_FAILED");
    }
  }

  @Override
  public ToolSession open() {
    return new Session();
  }

  private final class Session implements ToolSession {
    private final Map<String, McpSyncClient> clients = new HashMap<>();
    private final Map<String, Tool> definitions = new LinkedHashMap<>();
    private final Set<String> catalogTools = new HashSet<>();
    private final Set<String> traceIds = new HashSet<>();

    Session() {
      try {
        for (var entry : processes.entrySet()) {
          var client = connect(entry.getValue());
          clients.put(entry.getKey(), client);
          String cursor = null;
          int pages = 0;
          do {
            var page = client.listTools(cursor);
            for (var t : page.tools()) {
              register(entry.getKey(), t.name(), t.description(), t.inputSchema(), false);
            }
            cursor = page.nextCursor();
            if (++pages > 10) {
              throw new AgentFailure("MCP_CATALOG_LIMIT");
            }
          } while (cursor != null && !cursor.isBlank());
          if (entry.getKey().equals("sentry")
              && client.listTools().tools().stream()
                  .anyMatch(t -> t.name().equals("search_sentry_tools"))) {
            // Tool discovery is app-controlled; generic execution never bypasses policy.
            for (String query :
                List.of(
                    "search_issues search_issue_events get_issue_details get_issue_tag_values",
                    "find_releases get_release_details get_trace_details get_event_stacktrace")) {
              var result =
                  client.callTool(
                      new CallToolRequest(
                          "search_sentry_tools", Map.of("query", query, "limit", 20)));
              if (Boolean.TRUE.equals(result.isError())) {
                throw new AgentFailure("MCP_DISCOVERY_FAILED");
              }
              discover(Json.MAPPER.valueToTree(result.structuredContent()));
              for (var content : result.content()) {
                if (content instanceof TextContent text) {
                  try {
                    discover(Json.MAPPER.readTree(text.text()));
                  } catch (RuntimeException ignored) {
                    // Discovery text can contain prose instead of an embedded JSON catalog.
                  }
                }
              }
            }
          }
        }
      } catch (RuntimeException e) {
        close();
        throw e;
      }
    }

    private void discover(tools.jackson.databind.JsonNode node) {
      if (node == null) {
        return;
      }
      if (node.isObject() && node.has("name") && node.has("inputSchema")) {
        String name = node.path("name").asString();
        register(
            "sentry",
            name,
            node.path("description").asString(),
            Json.object(node.path("inputSchema").toString()),
            true);
      }
      if ((node.isObject() || node.isArray())) {
        for (var child : node) {
          discover(child);
        }
      }
    }

    private void register(
        String server,
        String name,
        String description,
        Map<String, Object> schema,
        boolean catalog) {
      if (!policy.allowed(server, name)) {
        return;
      }
      String alias = server + "__" + name;
      definitions.put(alias, new Tool(alias, Objects.toString(description, ""), schema));
      if (catalog) {
        catalogTools.add(alias);
      }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Tool> definitions() {
      return definitions.values().stream()
          .map(
              t -> {
                Map<String, Object> schema = new LinkedHashMap<>(t.schema());
                Map<String, Object> fields =
                    new LinkedHashMap<>(
                        (Map<String, Object>) schema.getOrDefault("properties", Map.of()));
                Set<String> bound =
                    Set.of(
                        "owner",
                        "repo",
                        "organizationSlug",
                        "projectSlug",
                        "projectSlugOrId",
                        "regionUrl",
                        "issueUrl",
                        "url");
                fields.keySet().removeAll(bound);
                schema.put("properties", fields);
                List<String> required =
                    new ArrayList<>((List<String>) schema.getOrDefault("required", List.of()));
                required.removeAll(bound);
                if (t.schema().toString().contains("issueUrl")
                    && fields.containsKey("issueId")
                    && !required.contains("issueId")) {
                  required.add("issueId");
                }
                schema.put("required", required);
                schema.put("additionalProperties", false);
                @SuppressWarnings("unchecked")
                Map<String, Object> compact = (Map<String, Object>) compactSchema(schema);
                String description =
                    t.description().substring(0, Math.min(t.description().length(), 350))
                        + " Scope is bound by the application.";
                if (t.name().startsWith("sentry__search_")) {
                  description +=
                      " This session has no embedded LLM. "
                          + "Use explicit Sentry search syntax instead of natural language.";
                }
                return new Tool(t.name(), description, compact);
              })
          .toList();
    }

    @Override
    public Object call(String alias, Map<String, Object> input) {
      Tool tool = definitions.get(alias);
      if (tool == null) {
        throw new IllegalArgumentException("Unknown tool");
      }
      String[] parts = alias.split("__", 2);
      var args = policy.bind(parts[0], parts[1], input, tool.schema(), traceIds);
      String name = parts[1];
      if (catalogTools.contains(alias)) {
        args = Map.of("name", name, "arguments", args);
        name = "execute_sentry_tool";
      }
      try {
        var result = clients.get(parts[0]).callTool(new CallToolRequest(name, args));
        if (Boolean.TRUE.equals(result.isError())) {
          String detail = redactor.clean(Json.encode(result.content()));
          throw new IllegalArgumentException(
              "MCP tool error: " + detail.substring(0, Math.min(detail.length(), 1000)));
        }
        List<Object> text = new ArrayList<>();
        if (result.structuredContent() != null) {
          text.add(result.structuredContent());
        }
        for (var content : result.content()) {
          if (content instanceof TextContent value) {
            text.add(value.text());
          }
        }
        String safe = redactor.clean(Json.encode(text));
        if (safe.length() > 131072) {
          throw new IllegalArgumentException("MCP result exceeds limit; narrow query");
        }
        if (parts[0].equals("sentry") && !parts[1].equals("get_trace_details")) {
          var matcher =
              java.util.regex.Pattern.compile(
                      "(?i)(?:trace[_ .-]?(?:id)?[\\\"\\s:=]+)([a-f0-9]{32})")
                  .matcher(safe);
          while (matcher.find()) {
            traceIds.add(matcher.group(1));
          }
        }
        return safe;
      } catch (IllegalArgumentException e) {
        throw e;
      } catch (RuntimeException e) {
        throw new IllegalArgumentException(
            "MCP read failed; choose another query or explain the gap");
      }
    }

    @Override
    public void close() {
      clients
          .values()
          .forEach(
              c -> {
                try {
                  c.closeGracefully();
                } catch (RuntimeException ignored) {
                  // Cleanup must not replace the result or failure of the tool session.
                }
              });
    }
  }

  private static Object compactSchema(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> result = new LinkedHashMap<>();
      map.forEach(
          (key, child) -> {
            if (!Set.of("description", "examples", "title", "default").contains(key.toString())) {
              result.put(key.toString(), compactSchema(child));
            }
          });
      return result;
    }
    if (value instanceof List<?> list) {
      return list.stream().map(StdioMcpTools::compactSchema).toList();
    }
    return value;
  }
}
