package dev.adriangm.investigator.adapter.out.mcp;

import dev.adriangm.investigator.adapter.support.Json;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import java.util.Map;

/** Validates tool arguments against the original schema discovered from the MCP server. */
public final class SchemaGuard {
    private SchemaGuard()
    {
    }

    /** Rejects arguments that do not satisfy the discovered original JSON schema. */
    public static void validate(Map<String, Object> schema, Map<String, Object> arguments)
    {
        var result = new DefaultJsonSchemaValidator(Json.MAPPER).validate(schema, arguments);
        if (!result.valid()) {
            throw new IllegalArgumentException("Arguments do not match discovered JSON schema");
        }
    }
}
