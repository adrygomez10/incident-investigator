package dev.adriangm.investigator.bootstrap;

import dev.adriangm.investigator.adapter.out.mcp.McpIssues;
import dev.adriangm.investigator.adapter.out.mcp.StdioMcpTools;
import dev.adriangm.investigator.adapter.out.mcp.ToolPolicy;
import dev.adriangm.investigator.adapter.out.model.ChatCompletionsModel;
import dev.adriangm.investigator.adapter.out.persistence.JdbcStore;
import dev.adriangm.investigator.adapter.out.persistence.MarkdownRetrieval;
import dev.adriangm.investigator.adapter.support.Redactor;
import dev.adriangm.investigator.application.Investigator;
import dev.adriangm.investigator.application.ManageInvestigations;
import dev.adriangm.investigator.application.Ports.AttemptBudget;
import dev.adriangm.investigator.application.Ports.InvestigationRepository;
import dev.adriangm.investigator.application.Ports.Issues;
import dev.adriangm.investigator.application.Ports.Model;
import dev.adriangm.investigator.application.Ports.Retrieval;
import dev.adriangm.investigator.application.Ports.Tools;
import dev.adriangm.investigator.application.Publisher;
import dev.adriangm.investigator.domain.ProjectScope;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Selects infrastructure implementations and connects the framework-independent application ports.
 */
@org.springframework.context.annotation.Configuration
public class Configuration {
    private final Environment env;

    /** Receives deployment configuration only at the composition boundary. */
    public Configuration(Environment env)
    {
        this.env = env;
    }

    private String value(String key, String fallback)
    {
        return env.getProperty(key, fallback);
    }

    private int number(String key, int fallback)
    {
        return Integer.parseInt(value(key, "" + fallback));
    }

    /** Configures secret removal from the deployment's credential values. */
    @Bean
    public Redactor redactor()
    {
        return new Redactor(
            List
                .of(
                    value("MODEL_API_KEY", ""),
                    value("GITHUB_READ_TOKEN", ""),
                    value("GITHUB_WRITE_TOKEN", ""),
                    value("SENTRY_ACCESS_TOKEN", ""),
                    value("OPERATOR_TOKEN", ""),
                    value("DB_PASSWORD", "")
                )
        );
    }

    /** Connects the repository, queue and attempt allowance to the JDBC implementation. */
    @Bean
    public JdbcStore store(JdbcTemplate jdbc, PlatformTransactionManager manager)
    {
        return new JdbcStore(jdbc, new TransactionTemplate(manager));
    }

    /** Connects lexical retrieval and verified document ingestion to PostgreSQL. */
    @Bean
    public MarkdownRetrieval retrieval(JdbcTemplate jdbc, PlatformTransactionManager manager)
    {
        return new MarkdownRetrieval(jdbc, new TransactionTemplate(manager));
    }

    /** Verifies and indexes the configured immutable corpus after dependency initialization. */
    @Bean
    public SmartInitializingSingleton ingest(MarkdownRetrieval retrieval)
    {
        return () -> {
            String revision = value("CORPUS_REVISION", "");
            if (!revision.isBlank()) {
                try {
                    retrieval.ingestVerified(Path.of(value("CORPUS_PATH", "corpus")), revision, "docs");
                } catch (java.io.IOException e) {
                    throw new IllegalStateException("Corpus could not be loaded");
                }
            }
        };
    }

    /** Resolves the fixed GitHub and Sentry scopes at the composition boundary. */
    @Bean
    public ToolPolicy toolPolicy()
    {
        return new ToolPolicy(
            value("GITHUB_REPOSITORY", "adrygomez10/orders-incident-demo"),
            value("SENTRY_ORGANIZATION", "configure-me"),
            value("SENTRY_PROJECT", "orders-incident-demo"),
            value("SENTRY_REGION", "https://sentry.io")
        );
    }

    private StdioMcpTools.ProcessConfig github(boolean write)
    {
        return new StdioMcpTools.ProcessConfig(
            value("GITHUB_MCP_COMMAND", "github-mcp-server"),
            write
                ? List.of("stdio", "--tools=issue_write,list_issues")
                : List.of("stdio", "--read-only", "--toolsets=repos,pull_requests,issues"),
            Map
                .of(
                    "GITHUB_PERSONAL_ACCESS_TOKEN",
                    value(write ? "GITHUB_WRITE_TOKEN" : "GITHUB_READ_TOKEN", ""),
                    "GITHUB_HOST",
                    "https://github.com"
                )
        );
    }

    /** Configures independent official read-only MCP processes without embedded AI providers. */
    @Bean
    public Tools tools(ToolPolicy policy, Redactor redactor)
    {
        var sentry = new StdioMcpTools.ProcessConfig(
            value("SENTRY_MCP_COMMAND", "node"),
            List
                .of(
                    value("SENTRY_MCP_SCRIPT", ".tools/sentry/node_modules/@sentry/mcp-server/dist/index.js"),
                    "--skills=inspect",
                    "--sentry-dsn="
                ),
            Map
                .of(
                    "SENTRY_ACCESS_TOKEN",
                    value("SENTRY_ACCESS_TOKEN", ""),
                    "OPENAI_API_KEY",
                    "",
                    "ANTHROPIC_API_KEY",
                    "",
                    "OPENROUTER_API_KEY",
                    "",
                    "AZURE_OPENAI_API_KEY",
                    "",
                    "EMBEDDED_AGENT_PROVIDER",
                    "",
                    "SENTRY_HOST",
                    "sentry.io"
                )
        );
        return new StdioMcpTools(Map.of("github", github(false), "sentry", sentry), policy, redactor);
    }

    /** Configures a separate MCP process for approved writes and receipt reconciliation. */
    @Bean
    public Issues issues()
    {
        return new McpIssues(github(true));
    }

    /** Connects the selected Chat Completions endpoint with its explicit pacing policy. */
    @Bean
    public Model model(Redactor redactor)
    {
        return new ChatCompletionsModel(
            value("MODEL_BASE_URL", "https://api.groq.com/openai/v1"),
            value("MODEL_API_KEY", ""),
            value("MODEL_NAME", "openai/gpt-oss-20b"),
            redactor,
            number(
                "MODEL_MIN_INTERVAL_SECONDS",
                value("MODEL_BASE_URL", "https://api.groq.com/openai/v1").startsWith("https://api.groq.com/") ? 65 : 0
            )
        );
    }

    /** Connects the model-controlled loop with persistence and configurable budgets. */
    @Bean
    public Investigator investigator(
        Model model,
        Tools tools,
        Retrieval retrieval,
        InvestigationRepository store,
        AttemptBudget attempts
    )
    {
        return new Investigator(
            model,
            tools,
            retrieval,
            store,
            attempts,
            new Investigator.Limits(
                number("MAX_MODEL_CALLS", 8),
                number("MAX_TOOL_CALLS", 16),
                number("MAX_RUN_SECONDS", 360),
                number("MAX_CONTEXT_CHARS", 40000),
                number("MAX_RESULT_CHARS", 6000),
                number("MAX_DAILY_MODEL_ATTEMPTS", 200)
            ),
            Clock.systemUTC()
        );
    }

    /** Connects approved publication to its issue port and persisted aggregate. */
    @Bean
    public Publisher publisher(Issues issues, InvestigationRepository store)
    {
        return new Publisher(issues, store);
    }

    /** Connects operator commands to the fixed scope, sanitization and application clock. */
    @Bean
    public ManageInvestigations investigations(
        InvestigationRepository repository,
        Publisher publisher,
        Redactor redactor
    )
    {
        return new ManageInvestigations(
            repository,
            publisher,
            new ProjectScope(
                value("PROJECT_KEY", "orders-demo"),
                value("GITHUB_REPOSITORY", "adrygomez10/orders-incident-demo")
            ),
            redactor,
            Clock.systemUTC()
        );
    }
}
