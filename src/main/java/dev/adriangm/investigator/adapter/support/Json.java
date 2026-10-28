package dev.adriangm.investigator.adapter.support;

import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** JSON conversion shared by technical adapters without adding serialization to the domain. */
public final class Json {
  /** Shared adapter mapper; the domain never depends on its serialization configuration. */
  public static final JsonMapper MAPPER = JsonMapper.builder().build();

  private Json() {}

  /** Serializes a technical adapter payload using the shared JSON configuration. */
  public static String encode(Object value) {
    return MAPPER.writeValueAsString(value);
  }

  /** Parses an external JSON object for translation at an adapter boundary. */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> object(String value) {
    return MAPPER.readValue(value, Map.class);
  }
}
