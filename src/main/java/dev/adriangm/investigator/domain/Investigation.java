package dev.adriangm.investigator.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Aggregate protecting the investigation lifecycle, evidence, draft versions and exact approval.
 */
public final class Investigation {
  /**
   * Business states of an investigation and its separately approved issue-publication lifecycle.
   */
  public enum Status {
    QUEUED,
    RUNNING,
    WAITING_CLARIFICATION,
    COMPLETED,
    BUDGET_EXHAUSTED,
    TEMPORARILY_UNAVAILABLE,
    INTERRUPTED,
    PUBLISH_QUEUED,
    PUBLISHING,
    PUBLISHED,
    PUBLISH_UNKNOWN
  }

  /** Stable citation identity and the exact source excerpt observed during an investigation. */
  public record Evidence(
      String id,
      String source,
      String url,
      Instant observedAt,
      String reference,
      String excerpt,
      String sha256,
      boolean truncated) {
    /** Requires a stable evidence identity, observation time and non-null source snapshot. */
    public Evidence {
      Objects.requireNonNull(observedAt);
      if (id == null || !id.matches("E[1-9]\\d*")) {
        throw new IllegalArgumentException("Invalid evidence ID");
      }
      Objects.requireNonNull(source);
      Objects.requireNonNull(url);
      Objects.requireNonNull(reference);
      Objects.requireNonNull(excerpt);
      Objects.requireNonNull(sha256);
    }
  }

  /** Versioned issue content bound to its repository by a fingerprint created by the aggregate. */
  public record Draft(int version, String title, String body, String hash) {
    /**
     * Requires a positive version, bounded content and a syntactically valid SHA-256 fingerprint.
     */
    public Draft {
      if (version < 1) {
        throw new IllegalArgumentException("Invalid draft version");
      }
      TextConstraints.require(title, 180);
      TextConstraints.require(body, 16000);
      if (hash == null || !hash.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException("Invalid draft fingerprint");
      }
    }
  }

  /** Exact issue payload and reconciliation marker released by an approved publication state. */
  public record ApprovedIssue(String repository, String title, String body, String marker) {}

  /** Immutable business state used to reconstitute the aggregate. */
  public record Snapshot(
      UUID id,
      ProjectScope scope,
      String question,
      InvestigationWindow window,
      Instant createdAt,
      Status status,
      List<Evidence> evidence,
      Diagnosis diagnosis,
      Draft draft,
      String clarification,
      String failure,
      String approvalKey,
      String approvedHash,
      String issueUrl) {
    /** Copies the evidence collection so a persisted snapshot cannot mutate the aggregate. */
    public Snapshot {
      evidence = List.copyOf(evidence);
    }
  }

  private final UUID id;
  private final ProjectScope scope;
  private final String question;
  private final InvestigationWindow window;
  private final Instant createdAt;
  private final List<Evidence> evidence;
  private Status status;
  private Diagnosis diagnosis;
  private Draft draft;
  private String clarification;
  private String failure;
  private String approvalKey;
  private String approvedHash;
  private String issueUrl;

  private Investigation(Snapshot state) {
    id = Objects.requireNonNull(state.id());
    scope = Objects.requireNonNull(state.scope());
    question = TextConstraints.require(state.question(), 4000);
    window = Objects.requireNonNull(state.window());
    createdAt = Objects.requireNonNull(state.createdAt());
    status = Objects.requireNonNull(state.status());
    evidence = new ArrayList<>(state.evidence());
    diagnosis = Objects.requireNonNull(state.diagnosis());
    draft = state.draft();
    clarification = Objects.requireNonNull(state.clarification());
    failure = Objects.requireNonNull(state.failure());
    approvalKey = Objects.requireNonNull(state.approvalKey());
    approvedHash = Objects.requireNonNull(state.approvedHash());
    issueUrl = Objects.requireNonNull(state.issueUrl());
    validateRestoredState();
  }

  /** Creates queued business state with no evidence, draft or approval. */
  public static Investigation create(
      UUID id, ProjectScope scope, String question, InvestigationWindow window, Instant createdAt) {
    return new Investigation(
        new Snapshot(
            id,
            scope,
            question,
            window,
            createdAt,
            Status.QUEUED,
            List.of(),
            Diagnosis.empty(),
            null,
            "",
            "",
            "",
            "",
            ""));
  }

  /**
   * Reconstitutes persisted state after validating evidence sequence, draft fingerprint and
   * approval.
   */
  public static Investigation restore(Snapshot snapshot) {
    return new Investigation(snapshot);
  }

  /** Returns an immutable business snapshot for a persistence adapter. */
  public Snapshot snapshot() {
    return new Snapshot(
        id,
        scope,
        question,
        window,
        createdAt,
        status,
        evidence,
        diagnosis,
        draft,
        clarification,
        failure,
        approvalKey,
        approvedHash,
        issueUrl);
  }

  /** Returns the stable investigation identity. */
  public UUID id() {
    return id;
  }

  /** Returns the logical project and authorized repository. */
  public ProjectScope scope() {
    return scope;
  }

  /** Returns the bounded question that opened the investigation. */
  public String question() {
    return question;
  }

  /** Returns the resolved interval used to delimit observations. */
  public InvestigationWindow window() {
    return window;
  }

  /** Returns the instant at which the investigation was created. */
  public Instant createdAt() {
    return createdAt;
  }

  /** Returns the current state without allowing an arbitrary state assignment. */
  public Status status() {
    return status;
  }

  /** Returns immutable source snapshots identified for citation. */
  public List<Evidence> evidence() {
    return List.copyOf(evidence);
  }

  /** Returns the immutable conclusion last accepted by the aggregate. */
  public Diagnosis diagnosis() {
    return diagnosis;
  }

  /** Returns the current versioned draft, or no draft before one has been prepared. */
  public Draft draft() {
    return draft;
  }

  /** Returns the outstanding question when the operator must provide essential information. */
  public String clarification() {
    return clarification;
  }

  /** Returns the recorded stop reason or unresolved publication limitation. */
  public String failure() {
    return failure;
  }

  /** Returns the idempotency key bound to the reserved publication. */
  public String approvalKey() {
    return approvalKey;
  }

  /** Returns the exact content fingerprint reserved for publication. */
  public String approvedHash() {
    return approvedHash;
  }

  /** Returns the confirmed issue receipt, or an empty value before confirmation. */
  public String issueUrl() {
    return issueUrl;
  }

  /** Claims queued investigation or approved publication work and rejects every other state. */
  public void claimPendingWork() {
    status =
        switch (status) {
          case QUEUED -> Status.RUNNING;
          case PUBLISH_QUEUED -> Status.PUBLISHING;
          default -> throw new IllegalStateException("Investigation has no pending work");
        };
  }

  /** Appends a sequentially identified source snapshot only during active investigation. */
  public Evidence recordEvidence(
      String source, String url, Instant at, String reference, String excerpt, boolean truncated) {
    requireStatus(Status.RUNNING);
    Evidence next =
        new Evidence(
            "E" + (evidence.size() + 1),
            source,
            url,
            at,
            reference,
            excerpt,
            Fingerprints.sha256(excerpt),
            truncated);
    evidence.add(next);
    return next;
  }

  /** Suspends active investigation until the operator supplies the missing information. */
  public void requestClarification(String question) {
    requireStatus(Status.RUNNING);
    clarification = TextConstraints.require(question, 1000);
    status = Status.WAITING_CLARIFICATION;
  }

  /**
   * Validates a clarification and requeues work; the application separately retains the message.
   */
  public void continueWith(String answer) {
    requireStatus(Status.WAITING_CLARIFICATION);
    TextConstraints.require(answer, 4000);
    clarification = "";
    failure = "";
    status = Status.QUEUED;
  }

  /**
   * Validates citations and builds the first draft before atomically completing the investigation.
   */
  public void complete(Diagnosis result, String title, String body) {
    requireStatus(Status.RUNNING);
    Set<String> known = new HashSet<>();
    evidence.forEach(value -> known.add(value.id()));
    result.validateAgainst(known, title + "\n" + body);

    Draft candidate = buildDraft(title, body, 1);

    diagnosis = result;
    draft = candidate;
    status = Status.COMPLETED;
    failure = "";
  }

  /** Retains the stop reason when active execution exhausts a budget or loses a dependency. */
  public void stop(Status reason, String detail) {
    requireStatus(Status.RUNNING);
    if (!Set.of(Status.BUDGET_EXHAUSTED, Status.TEMPORARILY_UNAVAILABLE).contains(reason)) {
      throw new IllegalArgumentException("Invalid execution stop reason");
    }
    failure = TextConstraints.require(detail, 1000);
    status = reason;
  }

  /** Interrupts an expired read or makes an expired publication uncertain without retrying it. */
  public void expireLease() {
    if (status == Status.RUNNING) {
      status = Status.INTERRUPTED;
      failure = "WORKER_LEASE_EXPIRED";
    } else if (status == Status.PUBLISHING) {
      publicationUncertain();
    } else {
      throw new IllegalStateException("No active lease");
    }
  }

  /** Replaces editable draft content, advances its version and invalidates any earlier approval. */
  public void editDraft(String title, String body, int expectedVersion) {
    if (!Set.of(
            Status.COMPLETED,
            Status.BUDGET_EXHAUSTED,
            Status.TEMPORARILY_UNAVAILABLE,
            Status.INTERRUPTED)
        .contains(status)) {
      throw new IllegalStateException("Draft is locked");
    }
    int current = draft == null ? 0 : draft.version();
    if (current != expectedVersion) {
      throw new IllegalStateException("Stale draft");
    }

    draft = buildDraft(title, body, current + 1);
    approvalKey = "";
    approvedHash = "";
  }

  private Draft buildDraft(String title, String body, int version) {
    title = TextConstraints.require(title, 180);
    body = TextConstraints.require(body, 16000);
    String content =
        body.replaceAll("(?s)<!-- investigation:.*?-->", "").stripTrailing()
            + "\n\n"
            + marker(version);
    return new Draft(
        version, title, content, Fingerprints.fields(scope.repository(), title, content));
  }

  /**
   * Reserves only the exact reviewed draft and treats reuse of the same approval key idempotently.
   */
  public void approve(int expectedVersion, String expectedHash, String key) {
    TextConstraints.require(key, 100);
    if (draft == null || draft.version() != expectedVersion || !draft.hash().equals(expectedHash)) {
      throw new IllegalStateException("Approval does not match draft");
    }
    if (Set.of(Status.PUBLISH_QUEUED, Status.PUBLISHING, Status.PUBLISHED, Status.PUBLISH_UNKNOWN)
        .contains(status)) {
      if (!approvalKey.equals(key)) {
        throw new IllegalStateException("Approval already reserved");
      }
      return;
    }
    requireStatus(Status.COMPLETED);

    approvalKey = key;
    approvedHash = expectedHash;
    status = Status.PUBLISH_QUEUED;
  }

  /** Releases the exact payload only for a claimed or uncertain approved publication. */
  public ApprovedIssue approvedIssue() {
    if (!Set.of(Status.PUBLISHING, Status.PUBLISH_UNKNOWN).contains(status)
        || draft == null
        || !draft.hash().equals(approvedHash)) {
      throw new IllegalStateException("Missing approved draft");
    }
    return new ApprovedIssue(
        scope.repository(), draft.title(), draft.body(), marker(draft.version()));
  }

  /** Accepts a receipt only for the approved repository and completes publication. */
  public void publicationConfirmed(String url) {
    approvedIssue();
    requireIssueUrl(url);
    issueUrl = url;
    failure = "";
    status = Status.PUBLISHED;
  }

  /** Prevents another creation when the outcome of the claimed write is unknown. */
  public void publicationUncertain() {
    requireStatus(Status.PUBLISHING);
    status = Status.PUBLISH_UNKNOWN;
    failure = "WRITE_OUTCOME_UNKNOWN";
  }

  private String marker(int version) {
    return "<!-- investigation:" + id + ":draft:" + version + " -->";
  }

  private void requireStatus(Status expected) {
    if (status != expected) {
      throw new IllegalStateException("Invalid investigation state: " + status);
    }
  }

  private void requireIssueUrl(String url) {
    if (url == null
        || !url.matches(
            "https://github\\.com/"
                + java.util.regex.Pattern.quote(scope.repository())
                + "/issues/\\d+")) {
      throw new IllegalArgumentException("Invalid issue receipt");
    }
  }

  private void validateRestoredState() {
    for (int index = 0; index < evidence.size(); index++) {
      if (!evidence.get(index).id().equals("E" + (index + 1))) {
        throw new IllegalArgumentException("Invalid evidence sequence");
      }
    }
    if (draft != null
        && !draft
            .hash()
            .equals(Fingerprints.fields(scope.repository(), draft.title(), draft.body()))) {
      throw new IllegalArgumentException("Draft fingerprint does not match its content");
    }
    if (Set.of(Status.PUBLISH_QUEUED, Status.PUBLISHING, Status.PUBLISHED, Status.PUBLISH_UNKNOWN)
            .contains(status)
        && (draft == null || approvalKey.isBlank() || !draft.hash().equals(approvedHash))) {
      throw new IllegalArgumentException("Publication state requires exact approval");
    }
    if (status == Status.PUBLISHED) {
      requireIssueUrl(issueUrl);
    }
  }
}
