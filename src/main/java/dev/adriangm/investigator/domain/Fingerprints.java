package dev.adriangm.investigator.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Deterministic UTF-8 SHA-256 fingerprints for content and unambiguously framed fields. */
public final class Fingerprints {
  private Fingerprints() {}

  /** Returns the lowercase hexadecimal SHA-256 fingerprint of UTF-8 text. */
  public static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 is unavailable", failure);
    }
  }

  /** Fingerprints length-framed fields while distinguishing omitted fields from empty strings. */
  public static String fields(String... values) {
    StringBuilder framed = new StringBuilder();
    for (String value : values) {
      if (value == null) {
        framed.append("-1:");
      } else {
        framed.append(value.length()).append(':').append(value);
      }
    }
    return sha256(framed.toString());
  }
}
