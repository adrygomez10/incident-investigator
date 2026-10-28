package dev.adriangm.investigator.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.adriangm.investigator.InvestigationFixtures;
import dev.adriangm.investigator.adapter.support.Json;
import dev.adriangm.investigator.application.InvestigationWork;
import dev.adriangm.investigator.domain.Fingerprints;
import dev.adriangm.investigator.domain.Investigation.Status;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcIntegrationTest {
  static JdbcTemplate jdbc;
  static JdbcStore store;
  static MarkdownRetrieval retrieval;
  static String schema;

  @BeforeAll
  static void setup() {
    String url = System.getenv("TEST_JDBC_URL");
    Assumptions.assumeTrue(url != null, "Set TEST_JDBC_URL to a disposable PostgreSQL database");
    var source =
        new DriverManagerDataSource(
            url,
            System.getenv().getOrDefault("TEST_DB_USER", "investigator_test"),
            System.getenv().getOrDefault("TEST_DB_PASSWORD", "test-only"));
    schema = "it_" + UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(source).execute("CREATE SCHEMA " + schema);
    var scoped =
        new DriverManagerDataSource(
            url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
            source.getUsername(),
            source.getPassword());
    Flyway.configure().dataSource(scoped).schemas(schema).defaultSchema(schema).load().migrate();
    jdbc = new JdbcTemplate(scoped);
    var tx = new TransactionTemplate(new DataSourceTransactionManager(scoped));
    store = new JdbcStore(jdbc, tx);
    retrieval = new MarkdownRetrieval(jdbc, tx);
  }

  @AfterAll
  static void cleanup() {
    if (jdbc != null) {
      jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
    }
  }

  static InvestigationWork fresh() {
    return InvestigationFixtures.fresh();
  }

  @Test
  void storesSnapshotsAndRejectsOldVersions() {
    var item = store.create(fresh(), UUID.randomUUID().toString());
    final var old = store.load(item.investigation().id());
    item.investigation().claimPendingWork();
    item.investigation()
        .recordEvidence("sentry", "", java.time.Instant.now(), "event-1", "saved finding", false);
    store.save(item);
    assertEquals(
        "saved finding",
        store.load(item.investigation().id()).investigation().evidence().getFirst().excerpt());
    assertThrows(IllegalStateException.class, () -> store.save(old));
  }

  @Test
  void idempotencyChecksOriginalInput() {
    String key = UUID.randomUUID().toString();
    var first = store.create(fresh(), key);
    assertEquals(first.investigation().id(), store.create(fresh(), key).investigation().id());
    var other = InvestigationFixtures.fresh("Different question");
    assertThrows(IllegalStateException.class, () -> store.create(other, key));
  }

  @Test
  void onlyOneWorkerClaimsEachJob() throws Exception {
    jdbc.update("DELETE FROM investigation");
    store.create(fresh(), UUID.randomUUID().toString());
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var results =
          executor.invokeAll(
              List.<Callable<Boolean>>of(
                  () -> store.claim().isPresent(), () -> store.claim().isPresent()));
      int claimed = 0;
      for (var result : results) {
        if (result.get()) {
          claimed++;
        }
      }
      assertEquals(1, claimed);
    }
  }

  @Test
  void restartInterruptsExpiredReadsAndMarksWritesUnknown() {
    jdbc.update("DELETE FROM investigation");
    var read = store.create(fresh(), UUID.randomUUID().toString());
    read.investigation().claimPendingWork();
    store.save(read);
    var write = store.create(InvestigationFixtures.completed(), UUID.randomUUID().toString());
    write.investigation().approve(1, write.investigation().draft().hash(), "approve");
    write.investigation().claimPendingWork();
    store.save(write);
    jdbc.update("UPDATE investigation SET lease_until=now()-interval '1 second'");
    store.recoverExpired();
    assertEquals(
        Status.INTERRUPTED, store.load(read.investigation().id()).investigation().status());
    assertEquals(
        Status.PUBLISH_UNKNOWN, store.load(write.investigation().id()).investigation().status());
    assertTrue(store.claim().isEmpty());
  }

  @Test
  void dailyBudgetIsAtomic() throws Exception {
    jdbc.update("DELETE FROM model_daily");
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var results =
          executor.invokeAll(
              List.<Callable<Boolean>>of(
                  () -> store.reserveModelAttempt(1), () -> store.reserveModelAttempt(1)));
      int accepted = 0;
      for (var result : results) {
        if (result.get()) {
          accepted++;
        }
      }
      assertEquals(1, accepted);
    }
  }

  @Test
  void snapshotManifestDetectsModifiedBytes() throws Exception {
    Path root = Files.createTempDirectory("incident-manifest-");
    Path doc = root.resolve("contract.md");
    Files.writeString(doc, "# Contract\ncustomerReference is optional\n");
    String sha = Fingerprints.sha256(Files.readString(doc));
    Files.writeString(
        root.resolve("manifest.json"),
        Json.encode(Map.of("revision", "a".repeat(40), "files", Map.of("contract.md", sha))));
    assertEquals(1, retrieval.ingestVerified(root, "a".repeat(40), "docs"));
    Files.writeString(doc, "# Altered\nUnreviewed text\n");
    assertThrows(
        IllegalArgumentException.class,
        () -> retrieval.ingestVerified(root, "a".repeat(40), "docs"));
    assertFalse(retrieval.search("customerReference", 5).isEmpty());
    Files.delete(doc);
    Files.delete(root.resolve("manifest.json"));
    Files.delete(root);
  }

  @Test
  void retrievesTechnicalNamesAndRemovesDeletedPassages() throws Exception {
    Path root = Files.createTempDirectory("incident-corpus-");
    Path doc = root.resolve("components.md");
    Files.writeString(
        doc, "# API\ncustomerReference is optional. Missing references become empty strings.\n");
    assertEquals(1, retrieval.ingest(root, "a".repeat(40), "docs"));
    var hits = retrieval.search("customerReference", 5);
    assertEquals(1, hits.size());
    assertEquals(1, hits.getFirst().firstLine());
    assertEquals(2, hits.getFirst().lastLine());
    assertTrue(retrieval.search("unrelatedimpossibleterm", 5).isEmpty());
    Files.delete(doc);
    retrieval.ingest(root, "b".repeat(40), "docs");
    assertTrue(retrieval.search("customerReference", 5).isEmpty());
    Files.delete(root);
  }
}
