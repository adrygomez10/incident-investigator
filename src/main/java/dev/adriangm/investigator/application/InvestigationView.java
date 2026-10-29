package dev.adriangm.investigator.application;

import dev.adriangm.investigator.domain.Diagnosis;
import dev.adriangm.investigator.domain.Investigation;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read projection combining business state with the execution journal for the private API. */
public record InvestigationView(
    UUID id,
    long version,
    String projectKey,
    String repository,
    String question,
    String since,
    String until,
    String requestFingerprint,
    Investigation.Status status,
    Instant createdAt,
    List<Map<String, Object>> messages,
    List<Investigation.Evidence> evidence,
    List<InvestigationWork.Step> steps,
    Diagnosis diagnosis,
    Map<String, String> modelConfiguration,
    Investigation.Draft draft,
    String clarification,
    String failure,
    String approvalKey,
    String approvedHash,
    String issueUrl,
    int modelCalls,
    int toolCalls,
    long inputTokens,
    long outputTokens
) {
    /** Builds the API projection without serializing the domain aggregate directly. */
    public static InvestigationView from(InvestigationWork work)
    {
        Investigation aggregate = work.investigation();
        return new InvestigationView(
            aggregate.id(),
            work.version(),
            aggregate.scope().projectKey(),
            aggregate.scope().repository(),
            aggregate.question(),
            aggregate.window().since().toString(),
            aggregate.window().until().toString(),
            work.requestFingerprint(),
            aggregate.status(),
            aggregate.createdAt(),
            work.messages(),
            aggregate.evidence(),
            work.steps(),
            aggregate.diagnosis(),
            work.modelConfiguration(),
            aggregate.draft(),
            aggregate.clarification(),
            aggregate.failure(),
            aggregate.approvalKey(),
            aggregate.approvedHash(),
            aggregate.issueUrl(),
            work.modelCalls(),
            work.toolCalls(),
            work.inputTokens(),
            work.outputTokens()
        );
    }
}
