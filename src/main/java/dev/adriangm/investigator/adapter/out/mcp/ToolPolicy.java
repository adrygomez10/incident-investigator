package dev.adriangm.investigator.adapter.out.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Restricts discovered tools and binds their arguments to the configured investigation scope. */
public final class ToolPolicy {
    /** GitHub operations that can be offered to the investigating model. */
    public static final Set<String> GITHUB_READ = Set
        .of(
            "get_file_contents",
            "get_commit",
            "list_commits",
            "list_pull_requests",
            "pull_request_read",
            "issue_read",
            "list_issues"
        );

    /** Direct Sentry reads allowed without embedded model inference. */
    public static final Set<String> SENTRY_READ = Set
        .of(
            "search_issues",
            "search_issue_events",
            "get_issue_details",
            "get_issue_tag_values",
            "get_event_stacktrace",
            "get_trace_details",
            "find_releases",
            "get_release_details"
        );

    private final String owner;
    private final String repo;
    private final String org;
    private final String project;
    private final String region;

    /** Validates the fixed repository, Sentry organization, project and allowed region. */
    public ToolPolicy(String repository, String org, String project, String region)
    {
        String[] pair = repository.split("/", -1);
        if (pair.length != 2 || !repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("Invalid repository");
        }
        if (!org.matches("[a-z0-9-]+") || !project.matches("[a-z0-9-]+")) {
            throw new IllegalArgumentException("Invalid Sentry scope");
        }
        if (!Set.of("https://sentry.io", "https://us.sentry.io", "https://de.sentry.io").contains(region)) {
            throw new IllegalArgumentException("Invalid Sentry region");
        }
        owner = pair[0];
        repo = pair[1];
        this.org = org;
        this.project = project;
        this.region = region;
    }

    /** Checks the explicit read allowlist independently of server-provided annotations. */
    public boolean allowed(String server, String name)
    {
        return (server.equals("github") ? GITHUB_READ : SENTRY_READ).contains(name);
    }

    /** Rejects scope overrides, binds trusted scope and validates the original argument schema. */
    public Map<String, Object> bind(
        String server,
        String name,
        Map<String, Object> input,
        Map<String, Object> schema,
        Set<String> traceIds
    )
    {
        if (!allowed(server, name)) {
            throw new IllegalArgumentException("Tool is not allowed");
        }
        Map<String, Object> args = new LinkedHashMap<>(input);
        if (server.equals("github")) {
            scope(args, "owner", owner);
            scope(args, "repo", repo);
            if (
                args.containsKey("method") && !Set
                    .of("get", "get_diff", "get_files", "get_commits", "get_comments")
                    .contains(args.get("method"))
            ) {
                throw new IllegalArgumentException("Method is not allowed");
            }
            String path = Objects.toString(args.get("path"), "");
            if (
                path.contains("..") || path.startsWith("/") || path.contains("\\")
                    || path.toLowerCase(Locale.ROOT).matches(".*(^|/)(\\.env[^/]*|\\.git|secrets?)(/.*)?$")
            ) {
                throw new IllegalArgumentException("Path is not allowed");
            }
        } else {
            scope(args, "organizationSlug", org);
            @SuppressWarnings("unchecked")
            Map<String, Object> fields = (Map<String, Object>) schema.getOrDefault("properties", Map.of());
            for (String field : List.of("projectSlug", "projectSlugOrId")) {
                if (fields.containsKey(field)) {
                    scope(args, field, project);
                }
            }
            if (fields.containsKey("regionUrl")) {
                scope(args, "regionUrl", region);
            }
            for (String field : List.of("issueUrl", "url")) {
                if (args.containsKey(field)) {
                    throw new IllegalArgumentException("Use a project-bound identifier");
                }
            }
            if (
                args.containsKey("issueId") && !Objects
                    .toString(args.get("issueId"))
                    .matches(java.util.regex.Pattern.quote(project.toUpperCase(Locale.ROOT)) + "-\\d+")
            ) {
                throw new IllegalArgumentException("Issue is outside project");
            }
            if (name.equals("get_trace_details") && !traceIds.contains(Objects.toString(args.get("traceId")))) {
                throw new IllegalArgumentException("Trace was not observed in scoped results");
            }
            if (args.containsKey("query")) {
                String query = Objects.toString(args.get("query"));
                if (query.matches("(?is).*\\b(project|organization|org):.*")) {
                    throw new IllegalArgumentException("Query scope is application-owned");
                }
            }
        }
        SchemaGuard.validate(schema, args);
        return args;
    }

    private static void scope(Map<String, Object> args, String key, String value)
    {
        if (args.get(key) != null && !args.get(key).equals(value)) {
            throw new IllegalArgumentException("Scope mismatch");
        }
        args.put(key, value);
    }
}
