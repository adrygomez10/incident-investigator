package dev.adriangm.investigator.adapter.support;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** Removes configured secrets and common sensitive-data patterns from recorded external content. */
public final class Redactor implements dev.adriangm.investigator.application.Ports.TextSanitizer {
  private final List<String> secrets;

  /** Keeps the non-empty configured secrets that must be excluded from recorded text. */
  public Redactor(Collection<String> secrets) {
    this.secrets = secrets.stream().filter(Objects::nonNull).filter(s -> s.length() >= 6).toList();
  }

  @Override
  public String clean(String text) {
    for (String value : secrets) {
      text = text.replace(value, "[REDACTED]");
    }
    return text.replaceAll("(?i)(bearer|sentry-bearer)\\s+[A-Za-z0-9_./+~=-]+", "$1 [REDACTED]")
        .replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", "[EMAIL]")
        .replaceAll(
            "(?i)\\b(gh[pousr]_[A-Za-z0-9_]+|github_pat_[A-Za-z0-9_]+"
                + "|gsk_[A-Za-z0-9_]+|sntrys_[A-Za-z0-9_]+)\\b",
            "[REDACTED]");
  }

  /**
   * Recursively copies technical content while removing configured secrets and sensitive patterns.
   */
  public Object clean(Object data) {
    return Json.MAPPER.readValue(clean(Json.encode(data)), Object.class);
  }
}
