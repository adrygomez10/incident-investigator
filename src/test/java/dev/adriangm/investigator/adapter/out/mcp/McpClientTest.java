package dev.adriangm.investigator.adapter.out.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.adriangm.investigator.adapter.support.Redactor;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class McpClientTest {
  @Test
  void realSdkDiscoversAndInvokesSeparateStdioProcesses() {
    var processes = new LinkedHashMap<String, StdioMcpTools.ProcessConfig>();
    for (String server : List.of("github", "sentry")) {
      processes.put(
          server,
          new StdioMcpTools.ProcessConfig(
              System.getenv().getOrDefault("PYTHON", "python"),
              List.of(
                  Path.of("src/test/resources/mcp_fixture.py").toAbsolutePath().toString(), server),
              Map.of()));
    }
    var tools =
        new StdioMcpTools(
            processes,
            new ToolPolicy("example/orders-demo", "example", "orders-demo", "https://sentry.io"),
            new Redactor(List.of()));
    try (var session = tools.open()) {
      assertEquals(2, session.definitions().size());
      assertFalse(session.definitions().toString().contains("execute_sentry_tool"));
      var result = session.call("github__get_commit", Map.of("sha", "abc"));
      assertTrue(result.toString().contains("TEST FIXTURE"));
      assertTrue(result.toString().contains("orders-demo"));
      var sentry = session.call("sentry__search_issues", Map.of("query", "level:error"));
      assertTrue(sentry.toString().contains("projectSlugOrId"));
      assertThrows(
          IllegalArgumentException.class,
          () -> session.call("github__get_commit", Map.of("sha", "abc", "repo", "other")));
      assertThrows(
          IllegalArgumentException.class,
          () -> session.call("sentry__execute_sentry_tool", Map.of("name", "update_issue")));
      assertThrows(
          IllegalArgumentException.class,
          () -> session.call("sentry__search_issues", Map.of("query", "project:other")));
      assertThrows(
          IllegalArgumentException.class,
          () -> session.call("sentry__search_issues", Map.of("name", "update_issue")));
    }
  }

  @Test
  void unobservedTraceAndForeignIssuesAreRejected() {
    var policy =
        new ToolPolicy("example/orders-demo", "example", "orders-demo", "https://sentry.io");
    var schema =
        Map.<String, Object>of(
            "type",
            "object",
            "properties",
            Map.of(
                "organizationSlug", Map.of("type", "string"), "issueId", Map.of("type", "string")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            policy.bind(
                "sentry", "get_issue_details", Map.of("issueId", "OTHER-1"), schema, Set.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            policy.bind(
                "sentry",
                "get_trace_details",
                Map.of("traceId", "a".repeat(32)),
                schema,
                Set.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            policy.bind(
                "github", "get_file_contents", Map.of("path", ".env.local"), Map.of(), Set.of()));
  }
}
