package dev.adriangm.investigator.adapter.out.persistence;

import dev.adriangm.investigator.adapter.support.Json;
import dev.adriangm.investigator.application.InvestigationWork;
import dev.adriangm.investigator.application.Ports.AttemptBudget;
import dev.adriangm.investigator.application.Ports.InvestigationRepository;
import dev.adriangm.investigator.application.Ports.WorkQueue;
import dev.adriangm.investigator.domain.TextConstraints;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Persists investigation snapshots, claims queued work and reserves daily model attempts in
 * PostgreSQL.
 */
public final class JdbcStore implements InvestigationRepository, WorkQueue, AttemptBudget {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  /** Receives JDBC access and the transaction boundary for work claims and recovery. */
  public JdbcStore(JdbcTemplate jdbc, TransactionTemplate transactions) {
    this.jdbc = jdbc;
    this.transactions = transactions;
  }

  private InvestigationWork read(ResultSet result, int row) throws SQLException {
    InvestigationWork work =
        InvestigationWork.restore(
            Json.MAPPER.readValue(result.getString("payload"), InvestigationWork.Snapshot.class));
    if (work.version() != result.getLong("version")
        || !work.investigation().status().name().equals(result.getString("status"))) {
      throw new IllegalStateException("Persisted state and concurrency metadata disagree");
    }
    return work;
  }

  @Override
  public InvestigationWork create(InvestigationWork work, String key) {
    TextConstraints.require(key, 100);
    jdbc.update(
        """
        INSERT INTO investigation(id,request_key,status,version,payload)
        VALUES (?,?,?,0,?::jsonb)
        ON CONFLICT (request_key) DO NOTHING
        """,
        work.investigation().id(),
        key,
        work.investigation().status().name(),
        Json.encode(work.snapshot(0)));
    InvestigationWork saved =
        jdbc.queryForObject("SELECT * FROM investigation WHERE request_key=?", this::read, key);
    if (!saved.requestFingerprint().equals(work.requestFingerprint())) {
      throw new IllegalStateException("Idempotency key reused with different input");
    }
    return saved;
  }

  @Override
  public InvestigationWork load(UUID id) {
    var found = jdbc.query("SELECT * FROM investigation WHERE id=?", this::read, id);
    if (found.isEmpty()) {
      throw new NoSuchElementException("Investigation not found");
    }
    return found.getFirst();
  }

  @Override
  public void save(InvestigationWork work) {
    long previous = work.version();
    long next = previous + 1;
    String status = work.investigation().status().name();
    int changed =
        jdbc.update(
            """
            UPDATE investigation SET payload=?::jsonb,status=?,version=?,
                lease_until=CASE WHEN ? IN ('RUNNING','PUBLISHING')
                THEN now()+interval '8 minutes' ELSE NULL END
            WHERE id=? AND version=?
            """,
            Json.encode(work.snapshot(next)),
            status,
            next,
            status,
            work.investigation().id(),
            previous);
    if (changed != 1) {
      throw new IllegalStateException("Concurrent modification");
    }
    work.acknowledgeSave(next);
  }

  @Override
  public Optional<InvestigationWork> claim() {
    return transactions.execute(
        transaction -> {
          var pending =
              jdbc.query(
                  """
                  SELECT * FROM investigation WHERE status IN ('QUEUED','PUBLISH_QUEUED')
                  ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1
                  """,
                  this::read);
          if (pending.isEmpty()) {
            return Optional.empty();
          }
          InvestigationWork work = pending.getFirst();
          work.investigation().claimPendingWork();
          save(work);
          return Optional.of(work);
        });
  }

  @Override
  public void recoverExpired() {
    transactions.executeWithoutResult(
        transaction -> {
          var expired =
              jdbc.query(
                  """
                  SELECT * FROM investigation WHERE status IN ('RUNNING','PUBLISHING')
                  AND lease_until<now() FOR UPDATE SKIP LOCKED
                  """,
                  this::read);
          for (InvestigationWork work : expired) {
            work.investigation().expireLease();
            save(work);
          }
        });
  }

  @Override
  public boolean reserveModelAttempt(int limit) {
    return jdbc.update(
            """
            INSERT INTO model_daily(day,attempts) VALUES ((now() AT TIME ZONE 'UTC')::date,1)
            ON CONFLICT(day) DO UPDATE SET attempts=model_daily.attempts+1
            WHERE model_daily.attempts<?
            """,
            limit)
        == 1;
  }
}
