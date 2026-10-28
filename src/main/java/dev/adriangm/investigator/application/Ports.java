package dev.adriangm.investigator.application;

import dev.adriangm.investigator.domain.Investigation.ApprovedIssue;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Framework-independent contracts and technical data exchanged at application boundaries. */
public final class Ports {
  private Ports() {}

  /** Discovered tool name, description and argument schema offered to the model. */
  public record Tool(String name, String description, Map<String, Object> schema) {}

  /** One model-proposed tool invocation, including the identifier used to return its result. */
  public record Call(String id, String name, Map<String, Object> arguments) {}

  /** Model response, proposed tool calls and provider-reported token usage. */
  public record Reply(
      Map<String, Object> message, List<Call> calls, long inputTokens, long outputTokens) {}

  /**
   * Retrieved Markdown passage with its path, lines, immutable revision and content fingerprint.
   */
  public record Passage(
      String id,
      String path,
      String heading,
      int firstLine,
      int lastLine,
      String revision,
      String sha256,
      String text) {}

  /**
   * Generates the next agent decision from the accumulated messages and authorized tool catalog.
   */
  public interface Model {
    /** Returns the next model decision from the authorized catalog and accumulated conversation. */
    Reply generate(List<Map<String, Object>> messages, List<Tool> tools);

    /** Returns the next model decision from the authorized catalog and accumulated conversation. */
    default Reply generate(
        List<Map<String, Object>> messages, List<Tool> tools, java.time.Duration remaining) {
      return generate(messages, tools);
    }

    /**
     * Returns non-secret provider identity; test implementations default to a controlled-model
     * label.
     */
    default Map<String, String> identity() {
      return Map.of("kind", "controlled-test-model");
    }
  }

  /** Lifecycle of a discovered, scoped tool catalog and its authorized invocations. */
  public interface ToolSession extends AutoCloseable {
    /** Returns the discovered catalog after local permission and scope restrictions. */
    List<Tool> definitions();

    /** Validates and executes one authorized read, returning data rather than instructions. */
    Object call(String name, Map<String, Object> arguments);

    @Override
    void close();
  }

  /** Opens read-only tool sessions for one investigation execution. */
  public interface Tools {
    /** Opens an independently closeable tool session for one execution. */
    ToolSession open();
  }

  /** Retrieves relevant passages without requiring a specific indexing technology. */
  public interface Retrieval {
    /** Returns up to the requested number of citable passages, including an empty result. */
    List<Passage> search(String query, int limit);
  }

  /** Stores the aggregate and its execution journal using optimistic version checks. */
  public interface InvestigationRepository {
    /**
     * Creates a request once or returns its original record; different input with the same key
     * conflicts.
     */
    InvestigationWork create(InvestigationWork item, String key);

    /** Loads the requested identity or reports its absence. */
    InvestigationWork load(UUID id);

    /** Persists business state and journal only if the prior storage version still matches. */
    void save(InvestigationWork item);
  }

  /** Claims persisted investigations or publications and recovers expired worker leases. */
  public interface WorkQueue {
    /** Atomically claims pending investigation or approved publication work. */
    Optional<InvestigationWork> claim();

    /** Preserves findings while interrupting expired reads and marking expired writes uncertain. */
    void recoverExpired();
  }

  /** Atomically reserves the provider attempts shared by the configured deployment. */
  public interface AttemptBudget {
    /** Atomically consumes one shared daily attempt if the configured allowance permits it. */
    boolean reserveModelAttempt(int dailyLimit);
  }

  /** Removes sensitive values before operator text enters persisted application state. */
  public interface TextSanitizer {
    /**
     * Returns bounded text suitable for retaining in the investigation without configured secrets.
     */
    String clean(String text);
  }

  /**
   * Creates only the supplied approved issue payload and reconciles uncertain results by marker.
   */
  public interface Issues {
    /**
     * Creates the supplied exact approved issue once; a lost response must be treated as uncertain.
     */
    String create(ApprovedIssue issue);

    /** Looks for the approved marker and returns a receipt without repeating the creation. */
    Optional<String> reconcile(ApprovedIssue issue);
  }
}
