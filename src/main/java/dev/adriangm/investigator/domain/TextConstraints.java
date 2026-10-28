package dev.adriangm.investigator.domain;

/** Shared length and non-blank requirements for bounded investigation text. */
public final class TextConstraints {
  private TextConstraints() {}

  /** Requires non-blank text no longer than the supplied character bound. */
  public static String require(String value, int maximum) {
    if (value == null || value.isBlank() || value.length() > maximum) {
      throw new IllegalArgumentException("Invalid text length");
    }
    return value;
  }
}
