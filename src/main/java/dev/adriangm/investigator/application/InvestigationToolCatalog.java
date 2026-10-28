package dev.adriangm.investigator.application;

import dev.adriangm.investigator.application.Ports.Tool;
import java.util.List;
import java.util.Map;

final class InvestigationToolCatalog {
    private InvestigationToolCatalog()
    {
    }

    private static Map<String, Object> stringField()
    {
        return Map.of("type", "string");
    }

    private static Tool terminal(String name, String description, Map<String, Object> properties, List<String> required)
    {
        return new Tool(
            name,
            description,
            Map.of("type", "object", "properties", properties, "required", required, "additionalProperties", false)
        );
    }

    static List<Tool> definitions()
    {
        return List
            .of(
                terminal(
                    "search_knowledge",
                    "Retrieve versioned internal Markdown passages",
                    Map.of("query", stringField()),
                    List.of("query")
                ),
                terminal(
                    "ask_clarification",
                    "Ask the operator for missing essential information",
                    Map.of("question", stringField()),
                    List.of("question")
                ),
                terminal(
                    "finish_investigation",
                    "Finish with facts, hypotheses, uncertainties, checks, evidence IDs and an issue draft",
                    Map
                        .of(
                            "facts",
                            Map.of("type", "array", "items", stringField()),
                            "hypotheses",
                            Map.of("type", "array", "items", stringField()),
                            "uncertainties",
                            Map.of("type", "array", "items", stringField()),
                            "recommendedChecks",
                            Map.of("type", "array", "items", stringField()),
                            "evidenceIds",
                            Map.of("type", "array", "items", stringField()),
                            "title",
                            stringField(),
                            "body",
                            stringField()
                        ),
                    List.of("facts", "hypotheses", "uncertainties", "recommendedChecks", "evidenceIds", "title", "body")
                )
            );
    }
}
