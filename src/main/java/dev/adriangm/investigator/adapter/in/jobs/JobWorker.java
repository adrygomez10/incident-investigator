package dev.adriangm.investigator.adapter.in.jobs;

import dev.adriangm.investigator.application.Investigator;
import dev.adriangm.investigator.application.Ports.WorkQueue;
import dev.adriangm.investigator.application.Publisher;
import dev.adriangm.investigator.domain.Investigation.Status;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Claims persisted work and delegates execution to the appropriate application use case. */
@Component
public final class JobWorker {
    private final WorkQueue store;
    private final Investigator investigator;
    private final Publisher publisher;

    /** Connects persisted work claims to investigation and publication use cases. */
    public JobWorker(WorkQueue store, Investigator investigator, Publisher publisher)
    {
        this.store = store;
        this.investigator = investigator;
        this.publisher = publisher;
    }

    /** Recovers expired leases and executes at most one newly claimed unit of work. */
    @Scheduled(fixedDelayString = "${WORKER_DELAY_MS:1000}")
    public void next()
    {
        store.recoverExpired();
        store.claim().ifPresent(item -> {
            if (item.investigation().status() == Status.PUBLISHING) {
                publisher.publish(item);
            } else {
                investigator.run(item);
            }
        });
    }
}
