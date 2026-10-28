package dev.adriangm.investigator.domain;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable facts, hypotheses, limitations, checks and declared evidence references. */
public record Diagnosis(
    List<String> facts,
    List<String> hypotheses,
    List<String> uncertainties,
    List<String> recommendedChecks,
    List<String> evidenceIds) {
  private static final Pattern REFERENCE = Pattern.compile("\\bE\\d+\\b");

  /** Defensively copies the diagnosis sections and their declared evidence identifiers. */
  public Diagnosis {
    facts = List.copyOf(facts);
    hypotheses = List.copyOf(hypotheses);
    uncertainties = List.copyOf(uncertainties);
    recommendedChecks = List.copyOf(recommendedChecks);
    evidenceIds = List.copyOf(evidenceIds);
  }

  /** Returns a diagnosis with no observed facts or declared evidence. */
  public static Diagnosis empty() {
    return new Diagnosis(List.of(), List.of(), List.of(), List.of(), List.of());
  }

  /** Rejects unknown or undeclared citations and facts without an evidence reference. */
  public void validateAgainst(Set<String> known, String draftText) {
    if (!known.containsAll(evidenceIds)) {
      throw new IllegalArgumentException("Unknown evidence ID");
    }
    for (String fact : facts) {
      if (!fact.matches("(?s).*\\[E\\d+\\].*")) {
        throw new IllegalArgumentException("Every fact needs an evidence citation");
      }
    }
    validateReferences(facts.toString(), known);
    validateReferences(hypotheses.toString(), known);
    validateReferences(uncertainties.toString(), known);
    validateReferences(recommendedChecks.toString(), known);
    validateReferences(draftText, known);
  }

  private void validateReferences(String text, Set<String> known) {
    var references = REFERENCE.matcher(text);
    while (references.find()) {
      if (!known.contains(references.group()) || !evidenceIds.contains(references.group())) {
        throw new IllegalArgumentException("Cited evidence is not in the supplied evidence IDs");
      }
    }
  }
}
