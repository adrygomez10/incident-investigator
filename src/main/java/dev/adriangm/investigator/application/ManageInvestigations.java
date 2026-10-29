package dev.adriangm.investigator.application;

import dev.adriangm.investigator.application.Ports.InvestigationRepository;
import dev.adriangm.investigator.application.Ports.TextSanitizer;
import dev.adriangm.investigator.domain.Fingerprints;
import dev.adriangm.investigator.domain.Investigation;
import dev.adriangm.investigator.domain.Investigation.Status;
import dev.adriangm.investigator.domain.InvestigationWindow;
import dev.adriangm.investigator.domain.ProjectScope;
import dev.adriangm.investigator.domain.TextConstraints;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Coordinates operator commands, sanitization, request identity and persistence through ports. */
public final class ManageInvestigations {
    /**
     * Operator request with optional temporal bounds; project scope is resolved by the application.
     */
    public record Start(String projectKey, String question, String since, String until) {
    }

    /** Clarification supplied against a known version of the investigation view. */
    public record Answer(long expectedVersion, String message) {
    }

    /** Requested issue-draft content and the version that the operator reviewed. */
    public record Edit(int expectedDraftVersion, String title, String body) {
    }

    /** Exact draft version and content fingerprint approved by the operator. */
    public record Approval(int draftVersion, String hash) {
    }

    private final InvestigationRepository repository;
    private final Publisher publisher;
    private final ProjectScope scope;
    private final TextSanitizer sanitizer;
    private final Clock clock;

    /** Receives the authorized scope, persistence and operator-command collaborators. */
    public ManageInvestigations(
        InvestigationRepository repository,
        Publisher publisher,
        ProjectScope scope,
        TextSanitizer sanitizer,
        Clock clock
    )
    {
        this.repository = repository;
        this.publisher = publisher;
        this.scope = scope;
        this.sanitizer = sanitizer;
        this.clock = clock;
    }

    /** Resolves scope and window and idempotently persists the operator's original request. */
    public InvestigationView start(String key, Start request)
    {
        if (!scope.projectKey().equals(request.projectKey())) {
            throw new IllegalArgumentException("Unknown project");
        }

        Instant until = request.until() == null ? clock.instant() : Instant.parse(request.until());
        Instant since = request.since() == null ? until.minus(Duration.ofDays(1)) : Instant.parse(request.since());
        String question = sanitizer.clean(TextConstraints.require(request.question(), 4000));

        Investigation aggregate = Investigation
            .create(UUID.randomUUID(), scope, question, new InvestigationWindow(since, until), clock.instant());
        String fingerprint = Fingerprints.fields(scope.projectKey(), question, request.since(), request.until());

        return InvestigationView.from(repository.create(new InvestigationWork(aggregate, fingerprint), key));
    }

    /** Loads the persisted investigation and returns its read projection. */
    public InvestigationView get(UUID id)
    {
        return InvestigationView.from(repository.load(id));
    }

    /** Sanitizes a clarification and requeues only the investigation version reviewed. */
    public InvestigationView answer(UUID id, Answer request)
    {
        InvestigationWork work = repository.load(id);
        work.requireVersion(request.expectedVersion());

        String answer = sanitizer.clean(TextConstraints.require(request.message(), 4000));
        work.investigation().continueWith(answer);
        work.appendMessage(java.util.Map.of("role", "user", "content", answer));
        repository.save(work);

        return InvestigationView.from(work);
    }

    /** Sanitizes draft content and delegates version and state checks to the aggregate. */
    public InvestigationView edit(UUID id, Edit request)
    {
        InvestigationWork work = repository.load(id);
        work
            .investigation()
            .editDraft(
                sanitizer.clean(TextConstraints.require(request.title(), 180)),
                sanitizer.clean(TextConstraints.require(request.body(), 16000)),
                request.expectedDraftVersion()
            );
        repository.save(work);

        return InvestigationView.from(work);
    }

    /**
     * Reserves the exact draft or reconciles an already uncertain publication without recreating it.
     */
    public InvestigationView approve(UUID id, String key, Approval request)
    {
        InvestigationWork work = repository.load(id);
        work.investigation().approve(request.draftVersion(), request.hash(), key);
        if (work.investigation().status() == Status.PUBLISH_UNKNOWN) {
            work = publisher.reconcile(work);
        } else if (work.investigation().status() == Status.PUBLISH_QUEUED) {
            try {
                repository.save(work);
            } catch (IllegalStateException conflict) {
                InvestigationWork latest = repository.load(id);
                if (
                    !key.equals(latest.investigation().approvalKey())
                        || !request.hash().equals(latest.investigation().approvedHash())
                ) {
                    throw conflict;
                }
                work = latest;
            }
        }
        return InvestigationView.from(work);
    }
}
