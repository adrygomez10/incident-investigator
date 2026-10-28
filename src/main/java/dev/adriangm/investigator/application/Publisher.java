package dev.adriangm.investigator.application;

import dev.adriangm.investigator.application.Ports.InvestigationRepository;
import dev.adriangm.investigator.application.Ports.Issues;
import dev.adriangm.investigator.domain.Investigation.Status;

/** Coordinates approved publication and preserves uncertainty instead of repeating a lost write. */
public final class Publisher {
  private final Issues issues;
  private final InvestigationRepository repository;

  /** Receives approved issue operations and version-checked investigation persistence. */
  public Publisher(Issues issues, InvestigationRepository repository) {
    this.issues = issues;
    this.repository = repository;
  }

  /**
   * Creates only claimed approved work and persists either a valid receipt or explicit uncertainty.
   */
  public void publish(InvestigationWork work) {
    var investigation = work.investigation();
    if (investigation.status() != Status.PUBLISHING) {
      throw new IllegalStateException("Publication was not claimed");
    }

    String receipt;
    try {
      receipt = issues.create(investigation.approvedIssue());
    } catch (RuntimeException failure) {
      investigation.publicationUncertain();
      repository.save(work);
      return;
    }

    try {
      investigation.publicationConfirmed(receipt);
    } catch (IllegalArgumentException failure) {
      investigation.publicationUncertain();
      repository.save(work);
      return;
    }

    repository.save(work);
  }

  /** Confirms an uncertain publication only when its marker yields a valid receipt. */
  public InvestigationWork reconcile(InvestigationWork work) {
    var investigation = work.investigation();
    if (investigation.status() != Status.PUBLISH_UNKNOWN) {
      return work;
    }
    var receipt = issues.reconcile(investigation.approvedIssue());
    if (receipt.isPresent()) {
      investigation.publicationConfirmed(receipt.get());
      repository.save(work);
    }
    return work;
  }
}
