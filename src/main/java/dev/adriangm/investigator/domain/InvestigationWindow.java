package dev.adriangm.investigator.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Positive UTC investigation interval bounded to ninety days. */
public record InvestigationWindow(Instant since, Instant until) {
  /** Rejects empty, reversed or excessively long investigation intervals. */
  public InvestigationWindow {
    Objects.requireNonNull(since);
    Objects.requireNonNull(until);
    if (!since.isBefore(until)
        || Duration.between(since, until).compareTo(Duration.ofDays(90)) > 0) {
      throw new IllegalArgumentException("Invalid investigation window");
    }
  }
}
