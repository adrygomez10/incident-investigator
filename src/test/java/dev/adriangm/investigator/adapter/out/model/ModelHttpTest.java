package dev.adriangm.investigator.adapter.out.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import dev.adriangm.investigator.adapter.support.Json;
import dev.adriangm.investigator.adapter.support.Redactor;
import dev.adriangm.investigator.application.AgentFailure;
import dev.adriangm.investigator.application.Investigator;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ModelHttpTest {
    @Test
    void sendsToolResultsAndDoesNotPersistReasoningFields() throws Exception
    {
        var request = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes()));
            byte[] body = Json
                .encode(
                    Map
                        .of(
                            "choices",
                            List
                                .of(
                                    Map
                                        .of(
                                            "finish_reason",
                                            "tool_calls",
                                            "message",
                                            Map
                                                .of(
                                                    "content",
                                                    "",
                                                    "reasoning",
                                                    "private chain",
                                                    "tool_calls",
                                                    List
                                                        .of(
                                                            Map
                                                                .of(
                                                                    "id",
                                                                    "c1",
                                                                    "function",
                                                                    Map
                                                                        .of(
                                                                            "name",
                                                                            "ask_clarification",
                                                                            "arguments",
                                                                            "{\"question\":\"Which release?\"}"
                                                                        )
                                                                )
                                                        )
                                                )
                                        )
                                ),
                            "usage",
                            Map.of("prompt_tokens", 17, "completion_tokens", 12)
                        )
                )
                .getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var model = new ChatCompletionsModel(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "",
                "test",
                new Redactor(List.of())
            );
            var response = model
                .generate(
                    List.of(Map.of("role", "tool", "tool_call_id", "previous", "content", Map.of("evidenceId", "E1"))),
                    Investigator.localTools()
                );
            assertEquals("ask_clarification", response.calls().getFirst().name());
            assertEquals(17, response.inputTokens());
            assertFalse(response.message().toString().contains("private chain"));
            assertTrue(Json.MAPPER.readTree(request.get()).path("messages").path(0).path("content").isTextual());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void quotaReturnsRetryAfterWithoutAnotherRequest() throws Exception
    {
        var count = new java.util.concurrent.atomic.AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            count.incrementAndGet();
            exchange.getResponseHeaders().set("Retry-After", "10");
            exchange.sendResponseHeaders(429, -1);
            exchange.close();
        });
        server.start();
        try {
            var model = new ChatCompletionsModel(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "",
                "test",
                new Redactor(List.of())
            );
            var failure = assertThrows(AgentFailure.class, () -> model.generate(List.of(), Investigator.localTools()));
            assertEquals(10, failure.retryAfterSeconds);
            assertThrows(AgentFailure.class, () -> model.generate(List.of(), Investigator.localTools()));
            assertEquals(1, count.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void pacingCannotExceedTheRemainingRunBudget() throws Exception
    {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var count = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/chat/completions", exchange -> {
            count.incrementAndGet();
            byte[] body = "{\"choices\":[{\"message\":{\"tool_calls\":[]}}]}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var model = new ChatCompletionsModel(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "",
                "test",
                new Redactor(List.of()),
                65
            );
            model.generate(List.of(), Investigator.localTools());
            var failure = assertThrows(
                AgentFailure.class,
                () -> model.generate(List.of(), Investigator.localTools(), java.time.Duration.ofMillis(20))
            );
            assertEquals("BUDGET_EXHAUSTED", failure.code);
            assertEquals(1, count.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void redactsCredentialsAndEmail()
    {
        var redactor = new Redactor(List.of("my-private-value"));
        String clean = redactor.clean("Bearer token-value alice@example.com my-private-value gsk_abc123");
        assertFalse(clean.contains("alice@"));
        assertFalse(clean.contains("my-private-value"));
        assertFalse(clean.contains("gsk_abc123"));
    }
}
