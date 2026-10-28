package dev.adriangm.investigator.adapter.out.model;

import dev.adriangm.investigator.adapter.support.Json;
import dev.adriangm.investigator.adapter.support.Redactor;
import dev.adriangm.investigator.application.AgentFailure;
import dev.adriangm.investigator.application.Ports.Call;
import dev.adriangm.investigator.application.Ports.Model;
import dev.adriangm.investigator.application.Ports.Reply;
import dev.adriangm.investigator.application.Ports.Tool;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Adapts the model port to Chat Completions with tool messages, pacing and quota failures. */
public final class ChatCompletionsModel implements Model {
  private final HttpClient http =
      HttpClient.newBuilder()
          .followRedirects(HttpClient.Redirect.NEVER)
          .connectTimeout(Duration.ofSeconds(10))
          .build();
  private final URI endpoint;
  private final String key;
  private final String model;
  private final Redactor redactor;
  private volatile Instant blockedUntil = Instant.EPOCH;
  private Instant nextRequest = Instant.EPOCH;
  private final int minimumIntervalSeconds;

  @Override
  public Map<String, String> identity() {
    return Map.of(
        "endpointHost", endpoint.getHost(), "model", model, "protocol", "chat-completions");
  }

  /** Validates the endpoint and configures credentials, model identity and request pacing. */
  public ChatCompletionsModel(String base, String key, String model, Redactor redactor) {
    this(base, key, model, redactor, URI.create(base).getHost().equals("api.groq.com") ? 65 : 0);
  }

  /** Validates the endpoint and configures credentials, model identity and request pacing. */
  public ChatCompletionsModel(
      String base, String key, String model, Redactor redactor, int minimumIntervalSeconds) {
    endpoint = URI.create(base.replaceAll("/+$", "") + "/chat/completions");
    if (!endpoint.getScheme().equals("https")
        && !Set.of("localhost", "127.0.0.1", "host.docker.internal", "model-fixture")
            .contains(endpoint.getHost())) {
      throw new IllegalArgumentException("Model endpoint must use HTTPS");
    }
    this.key = key;
    this.model = model;
    this.redactor = redactor;
    if (minimumIntervalSeconds < 0 || minimumIntervalSeconds > 300) {
      throw new IllegalArgumentException("Invalid provider interval");
    }
    this.minimumIntervalSeconds = minimumIntervalSeconds;
  }

  @Override
  public Reply generate(List<Map<String, Object>> messages, List<Tool> tools) {
    return generate(messages, tools, Duration.ofSeconds(360));
  }

  @Override
  public synchronized Reply generate(
      List<Map<String, Object>> messages, List<Tool> tools, Duration remaining) {
    if (Instant.now().isBefore(blockedUntil)) {
      throw new AgentFailure(
          "PROVIDER_RATE_LIMIT", Duration.between(Instant.now(), blockedUntil).toSeconds() + 1);
    }
    var wire = new ArrayList<Map<String, Object>>();
    for (var message : messages) {
      var copy = new LinkedHashMap<>(message);
      if (!(copy.get("content") instanceof String)) {
        copy.put("content", Json.encode(copy.get("content")));
      }
      wire.add(copy);
    }
    var definitions =
        tools.stream()
            .map(
                t ->
                    Map.of(
                        "type",
                        "function",
                        "function",
                        Map.of(
                            "name",
                            t.name(),
                            "description",
                            t.description(),
                            "parameters",
                            t.schema())))
            .toList();
    final String body =
        Json.encode(
            Map.of(
                "model",
                model,
                "messages",
                wire,
                "tools",
                definitions,
                "tool_choice",
                "auto",
                "parallel_tool_calls",
                false,
                "max_completion_tokens",
                2048));
    long pause = Math.max(0, Duration.between(Instant.now(), nextRequest).toMillis());
    if (remaining.toMillis() <= pause + 1000) {
      throw new AgentFailure("BUDGET_EXHAUSTED");
    }
    if (pause > 0) {
      try {
        Thread.sleep(pause);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AgentFailure("PROVIDER_INTERRUPTED");
      }
    }
    long requestSeconds = Math.min(45, Math.max(1, (remaining.toMillis() - pause) / 1000));
    nextRequest = Instant.now().plusSeconds(minimumIntervalSeconds);
    var request =
        HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(requestSeconds))
            .header("Content-Type", "application/json");
    if (!key.isBlank()) {
      request.header("Authorization", "Bearer " + key);
    }
    try {
      var response =
          http.send(
              request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
              HttpResponse.BodyHandlers.ofInputStream());
      try (var stream = response.body()) {
        if (response.statusCode() == 429) {
          long wait = retryAfter(response.headers().firstValue("retry-after").orElse("60"));
          blockedUntil = Instant.now().plusSeconds(wait);
          throw new AgentFailure("PROVIDER_RATE_LIMIT", wait);
        }
        if (response.statusCode() != 200) {
          throw new AgentFailure("PROVIDER_HTTP_" + response.statusCode());
        }
        byte[] data = stream.readNBytes(262145);
        if (data.length > 262144) {
          throw new AgentFailure("PROVIDER_RESPONSE_TOO_LARGE");
        }
        var root =
            Json.MAPPER.readTree(
                redactor.clean(new String(data, java.nio.charset.StandardCharsets.UTF_8)));
        var choice = root.path("choices").path(0);
        if (Set.of("length", "content_filter").contains(choice.path("finish_reason").asString())) {
          throw new AgentFailure("PROVIDER_INCOMPLETE_RESPONSE");
        }
        var incoming = choice.path("message");
        var calls = new ArrayList<Call>();
        var nativeCalls = new ArrayList<Map<String, Object>>();
        for (var call : incoming.path("tool_calls")) {
          String id = call.path("id").asString();
          String name = call.path("function").path("name").asString();
          var args = Json.object(call.path("function").path("arguments").asString());
          if (id.isBlank() || name.isBlank()) {
            throw new AgentFailure("PROVIDER_INVALID_TOOL_CALL");
          }
          calls.add(new Call(id, name, args));
          nativeCalls.add(
              Map.of(
                  "id",
                  id,
                  "type",
                  "function",
                  "function",
                  Map.of("name", name, "arguments", Json.encode(args))));
        }
        Map<String, Object> message =
            Map.of(
                "role",
                "assistant",
                "content",
                incoming.path("content").asString(""),
                "tool_calls",
                nativeCalls);
        return new Reply(
            message,
            calls,
            root.path("usage").path("prompt_tokens").asLong(0),
            root.path("usage").path("completion_tokens").asLong(0));
      }
    } catch (AgentFailure e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AgentFailure("PROVIDER_INTERRUPTED");
    } catch (Exception e) {
      throw new AgentFailure("PROVIDER_UNAVAILABLE");
    }
  }

  static long retryAfter(String value) {
    try {
      return Math.max(1, Long.parseLong(value));
    } catch (NumberFormatException e) {
      try {
        return Math.max(
            1,
            Duration.between(
                    Instant.now(),
                    ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant())
                .toSeconds());
      } catch (Exception ignored) {
        return 60;
      }
    }
  }
}
