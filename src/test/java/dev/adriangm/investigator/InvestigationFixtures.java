package dev.adriangm.investigator;

import dev.adriangm.investigator.application.InvestigationWork;
import dev.adriangm.investigator.domain.Diagnosis;
import dev.adriangm.investigator.domain.Fingerprints;
import dev.adriangm.investigator.domain.Investigation;
import dev.adriangm.investigator.domain.InvestigationWindow;
import dev.adriangm.investigator.domain.ProjectScope;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Test investigations built through valid business transitions rather than mutable field
 * assignments.
 */
public final class InvestigationFixtures {
    private InvestigationFixtures()
    {
    }

    /** Builds a queued investigation with a deterministic scope and temporal window. */
    public static InvestigationWork fresh()
    {
        return fresh("Investigate orders");
    }

    /** Builds a queued investigation with a deterministic scope and temporal window. */
    public static InvestigationWork fresh(String question)
    {
        var aggregate = Investigation
            .create(
                UUID.randomUUID(),
                new ProjectScope("orders-demo", "example/orders-demo"),
                question,
                new InvestigationWindow(Instant.parse("2026-10-26T10:00:00Z"), Instant.parse("2026-10-26T11:00:00Z")),
                Instant.parse("2026-10-26T11:00:00Z")
            );
        return new InvestigationWork(
            aggregate,
            Fingerprints.fields("orders-demo", question, "2026-10-26T10:00:00Z", "2026-10-26T11:00:00Z")
        );
    }

    /** Builds active investigation work through the aggregate's claim operation. */
    public static InvestigationWork running()
    {
        var work = fresh();
        work.investigation().claimPendingWork();
        return work;
    }

    /** Builds completed work using a recorded source, cited diagnosis and versioned draft. */
    public static InvestigationWork completed()
    {
        var work = running();
        var aggregate = work.investigation();
        aggregate
            .recordEvidence(
                "sentry",
                "https://sentry.io/organizations/example/issues/1/",
                Instant.now(),
                "event-1",
                "Observed error",
                false
            );
        aggregate
            .complete(
                new Diagnosis(List.of("Observed error [E1]"), List.of(), List.of(), List.of(), List.of("E1")),
                "Orders fail",
                "Observed failure [E1]"
            );
        return work;
    }
}
