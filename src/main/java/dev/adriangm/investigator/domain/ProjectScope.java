package dev.adriangm.investigator.domain;

/** Logical project identity and the repository authorized for its investigation. */
public record ProjectScope(String projectKey, String repository) {
  /** Rejects unknown-shaped logical identifiers and repository coordinates. */
  public ProjectScope {
    if (projectKey == null || !projectKey.matches("[A-Za-z0-9_.-]{1,100}")) {
      throw new IllegalArgumentException("Invalid project key");
    }
    if (repository == null || !repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
      throw new IllegalArgumentException("Invalid repository");
    }
  }
}
