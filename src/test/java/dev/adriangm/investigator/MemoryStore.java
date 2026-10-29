package dev.adriangm.investigator;

import dev.adriangm.investigator.application.InvestigationWork;
import dev.adriangm.investigator.application.Ports.AttemptBudget;
import dev.adriangm.investigator.application.Ports.InvestigationRepository;
import dev.adriangm.investigator.application.Ports.WorkQueue;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory repository test double preserving snapshots, request identity and optimistic versions.
 */
public class MemoryStore implements InvestigationRepository, AttemptBudget, WorkQueue {
    private final Map<UUID, InvestigationWork.Snapshot> saved = new HashMap<>();
    private final Map<String, UUID> keys = new HashMap<>();
    private int attempts;

    @Override
    public InvestigationWork create(InvestigationWork value, String key)
    {
        UUID existing = keys.get(key);
        if (existing != null) {
            var result = load(existing);
            if (!result.requestFingerprint().equals(value.requestFingerprint())) {
                throw new IllegalStateException("Idempotency conflict");
            }
            return result;
        }
        saved.put(value.investigation().id(), value.snapshot(0));
        keys.put(key, value.investigation().id());
        return load(value.investigation().id());
    }

    @Override
    public InvestigationWork load(UUID id)
    {
        if (!saved.containsKey(id)) {
            throw new NoSuchElementException();
        }
        return InvestigationWork.restore(saved.get(id));
    }

    @Override
    public void save(InvestigationWork value)
    {
        var existing = saved.get(value.investigation().id());
        if (existing != null && existing.version() != value.version()) {
            throw new IllegalStateException("Concurrent modification");
        }
        long next = value.version() + 1;
        saved.put(value.investigation().id(), value.snapshot(next));
        value.acknowledgeSave(next);
    }

    @Override
    public Optional<InvestigationWork> claim()
    {
        return Optional.empty();
    }

    @Override
    public void recoverExpired()
    {
    }

    @Override
    public boolean reserveModelAttempt(int limit)
    {
        return ++attempts <= limit;
    }
}
