package dev.adriangm.investigator.application;

import dev.adriangm.investigator.domain.Investigation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Application execution journal kept separately from the investigation aggregate's business rules.
 */
public final class InvestigationWork {
    /** One recorded catalog, request or result with a defensively copied technical payload. */
    public record Step(String kind, String name, Map<String, Object> arguments, Object result, Instant at) {
        /** Defensively copies request arguments and mutable result containers. */
        public Step
        {
            arguments = freezeMap(arguments);
            result = freeze(result);
        }
    }

    /** Immutable aggregate and technical journal composed at the persistence boundary. */
    public record Snapshot(
        Investigation.Snapshot investigation,
        long version,
        String requestFingerprint,
        List<Map<String, Object>> messages,
        List<Step> steps,
        Map<String, String> modelConfiguration,
        int modelCalls,
        int toolCalls,
        long inputTokens,
        long outputTokens
    ) {
    }

    private final Investigation investigation;
    private final String requestFingerprint;
    private long version;
    private final List<Map<String, Object>> messages = new ArrayList<>();
    private final List<Step> steps = new ArrayList<>();
    private Map<String, String> modelConfiguration = Map.of();
    private int modelCalls;
    private int toolCalls;
    private long inputTokens;
    private long outputTokens;

    /** Starts a separate execution journal for the aggregate and the original request fingerprint. */
    public InvestigationWork(Investigation investigation, String requestFingerprint)
    {
        this.investigation = Objects.requireNonNull(investigation);
        this.requestFingerprint = Objects.requireNonNull(requestFingerprint);
    }

    /** Reconstitutes an aggregate and its journal while validating persisted counters. */
    public static InvestigationWork restore(Snapshot snapshot)
    {
        var work = new InvestigationWork(
            Investigation.restore(snapshot.investigation()),
            snapshot.requestFingerprint()
        );

        if (
            snapshot.version() < 0 || snapshot.modelCalls() < 0 || snapshot.toolCalls() < 0
                || snapshot.inputTokens() < 0 || snapshot.outputTokens() < 0
        ) {
            throw new IllegalArgumentException("Invalid persisted execution counters");
        }

        work.version = snapshot.version();
        snapshot.messages().forEach(work::appendMessage);
        work.steps.addAll(snapshot.steps());
        work.modelConfiguration = Map.copyOf(snapshot.modelConfiguration());
        work.modelCalls = snapshot.modelCalls();
        work.toolCalls = snapshot.toolCalls();
        work.inputTokens = snapshot.inputTokens();
        work.outputTokens = snapshot.outputTokens();

        return work;
    }

    /** Creates a storage snapshot with the version to be committed by the repository. */
    public Snapshot snapshot(long storedVersion)
    {
        return new Snapshot(
            investigation.snapshot(),
            storedVersion,
            requestFingerprint,
            messages(),
            steps(),
            modelConfiguration,
            modelCalls,
            toolCalls,
            inputTokens,
            outputTokens
        );
    }

    /** Returns the aggregate whose named operations protect the business rules. */
    public Investigation investigation()
    {
        return investigation;
    }

    /** Returns the storage version used for optimistic concurrency checks. */
    public long version()
    {
        return version;
    }

    /** Returns the identity of the original request, including omitted temporal bounds. */
    public String requestFingerprint()
    {
        return requestFingerprint;
    }

    /** Returns an immutable view of the accumulated model conversation. */
    public List<Map<String, Object>> messages()
    {
        return List.copyOf(messages);
    }

    /** Returns an immutable view of the recorded execution steps. */
    public List<Step> steps()
    {
        return List.copyOf(steps);
    }

    /** Returns the non-secret model identity recorded for this execution. */
    public Map<String, String> modelConfiguration()
    {
        return modelConfiguration;
    }

    /** Returns the number of provider attempts retained across executions. */
    public int modelCalls()
    {
        return modelCalls;
    }

    /** Returns the number of model-proposed tool attempts retained by the journal. */
    public int toolCalls()
    {
        return toolCalls;
    }

    /** Returns the accumulated provider-reported input-token consumption. */
    public long inputTokens()
    {
        return inputTokens;
    }

    /** Returns the accumulated provider-reported output-token consumption. */
    public long outputTokens()
    {
        return outputTokens;
    }

    /** Rejects a command based on a stale persisted investigation version. */
    public void requireVersion(long expected)
    {
        if (version != expected) {
            throw new IllegalStateException("Stale investigation");
        }
    }

    /** Advances the local storage version only after a successful repository update. */
    public void acknowledgeSave(long next)
    {
        if (next != version + 1) {
            throw new IllegalStateException("Invalid storage version");
        }
        version = next;
    }

    /** Retains a defensively copied message so later mutations cannot change the journal. */
    public void appendMessage(Map<String, Object> message)
    {
        messages.add(freezeMap(message));
    }

    /** Appends one execution step to the technical journal. */
    public void recordStep(Step step)
    {
        steps.add(Objects.requireNonNull(step));
    }

    /** Retains an immutable copy of the non-secret provider configuration. */
    public void recordModelIdentity(Map<String, String> configuration)
    {
        modelConfiguration = Map.copyOf(configuration);
    }

    /** Counts a reserved model attempt before calling the provider. */
    public void recordModelAttempt()
    {
        modelCalls++;
    }

    /** Counts a proposed tool operation before validation or execution. */
    public void recordToolAttempt()
    {
        toolCalls++;
    }

    /** Adds non-negative token consumption returned by the provider. */
    public void recordUsage(long input, long output)
    {
        if (input < 0 || output < 0) {
            throw new IllegalArgumentException("Invalid token usage");
        }

        inputTokens += input;
        outputTokens += output;
    }

    private static Map<String, Object> freezeMap(Map<String, Object> source)
    {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, freeze(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object freeze(Object value)
    {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(key.toString(), freeze(item)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(InvestigationWork::freeze).toList();
        }
        return value;
    }
}
