package dev.adriangm.investigator.adapter.out.persistence;

import dev.adriangm.investigator.adapter.support.Json;
import dev.adriangm.investigator.application.Ports.Passage;
import dev.adriangm.investigator.application.Ports.Retrieval;
import dev.adriangm.investigator.domain.Fingerprints;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Indexes verified Markdown sections and retrieves citable passages with PostgreSQL lexical search.
 */
public final class MarkdownRetrieval implements Retrieval {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;

  /** Receives JDBC access and transactional replacement of the document index. */
  public MarkdownRetrieval(JdbcTemplate jdbc, TransactionTemplate tx) {
    this.jdbc = jdbc;
    this.tx = tx;
  }

  /** Verifies revision and byte hashes before transactionally replacing indexed passages. */
  public int ingestVerified(Path root, String revision, String prefix) throws java.io.IOException {
    var manifest = Json.MAPPER.readTree(Files.readString(root.resolve("manifest.json")));
    if (!revision.equals(manifest.path("revision").asString())) {
      throw new IllegalArgumentException("Corpus revision mismatch");
    }
    Set<String> found = new HashSet<>();
    try (var files = Files.walk(root)) {
      for (Path file : files.filter(p -> p.toString().endsWith(".md")).toList()) {
        if (Files.isSymbolicLink(file) || !file.toRealPath().startsWith(root.toRealPath())) {
          throw new IllegalArgumentException("Corpus file escapes snapshot");
        }
        String relative = root.relativize(file).toString().replace('\\', '/');
        found.add(relative);
        String hash =
            HexFormat.of()
                .formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                        .digest(Files.readAllBytes(file)));
        if (!hash.equals(manifest.path("files").path(relative).asString())) {
          throw new IllegalArgumentException("Corpus file does not match snapshot");
        }
      }
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    if (found.size() != manifest.path("files").size()) {
      throw new IllegalArgumentException("Incomplete corpus snapshot");
    }
    return ingest(root, revision, prefix);
  }

  /** Indexes the supplied Markdown snapshot while preserving path, revision and line ranges. */
  public int ingest(Path root, String revision, String prefix) throws java.io.IOException {
    if (!revision.matches("[a-f0-9]{40}")) {
      throw new IllegalArgumentException("Corpus revision must be an immutable Git SHA");
    }
    if (!prefix.matches("[a-zA-Z0-9_/-]+") || prefix.contains("..")) {
      throw new IllegalArgumentException("Invalid corpus prefix");
    }
    List<Passage> chunks = new ArrayList<>();
    try (var paths = Files.walk(root)) {
      for (Path path :
          paths
              .filter(
                  p ->
                      Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)
                          && p.toString().endsWith(".md"))
              .sorted()
              .toList()) {
        if (!path.toRealPath().startsWith(root.toRealPath())) {
          throw new IllegalArgumentException("Corpus path escapes root");
        }
        List<String> lines = Files.readAllLines(path);
        String heading = "Document";
        int start = 0;
        for (int i = 0; i <= lines.size(); i++) {
          if (i == lines.size() || lines.get(i).startsWith("#")) {
            if (i > start) {
              chunk(chunks, root, path, lines, start, i, heading, revision, prefix);
            }
            if (i < lines.size()) {
              heading = lines.get(i).replaceFirst("^#+\\s*", "");
            }
            start = i;
          }
        }
      }
    }
    tx.executeWithoutResult(
        status -> {
          jdbc.update("DELETE FROM knowledge_chunk");
          for (Passage p : chunks) {
            jdbc.update(
                "INSERT INTO knowledge_chunk"
                    + "(id,path,heading,first_line,last_line,revision,sha256,body) "
                    + "VALUES (?,?,?,?,?,?,?,?)",
                p.id(),
                p.path(),
                p.heading(),
                p.firstLine(),
                p.lastLine(),
                p.revision(),
                p.sha256(),
                p.text());
          }
        });
    return chunks.size();
  }

  private static void chunk(
      List<Passage> out,
      Path root,
      Path path,
      List<String> lines,
      int first,
      int last,
      String heading,
      String revision,
      String prefix) {
    for (int start = first; start < last; start += 35) {
      int end = Math.min(last, start + 40);
      String text = String.join("\n", lines.subList(start, end));
      if (text.isBlank()) {
        continue;
      }
      String name = prefix + "/" + root.relativize(path).toString().replace('\\', '/');
      String hash = Fingerprints.sha256(text);
      out.add(
          new Passage(
              Fingerprints.sha256(name + ":" + revision + ":" + start).substring(0, 24),
              name,
              heading,
              start + 1,
              end,
              revision,
              hash,
              text));
      if (end == last) {
        break;
      }
    }
  }

  @Override
  public List<Passage> search(String query, int limit) {
    return jdbc.query(
        """
        WITH q AS (
          SELECT websearch_to_tsquery('spanish', ?) es,
                 websearch_to_tsquery('simple', ?) tech
        )
        SELECT k.*,ts_rank_cd(k.search_es,q.es)+ts_rank_cd(k.search_tech,q.tech) score
        FROM knowledge_chunk k,q
        WHERE k.search_es@@q.es OR k.search_tech@@q.tech
        ORDER BY score DESC,k.path,k.first_line LIMIT ?
        """,
        (rs, row) ->
            new Passage(
                rs.getString("id"),
                rs.getString("path"),
                rs.getString("heading"),
                rs.getInt("first_line"),
                rs.getInt("last_line"),
                rs.getString("revision"),
                rs.getString("sha256"),
                rs.getString("body")),
        query,
        query,
        Math.min(5, Math.max(1, limit)));
  }
}
