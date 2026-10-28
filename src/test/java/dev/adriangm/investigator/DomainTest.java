package dev.adriangm.investigator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.adriangm.investigator.domain.Diagnosis;
import dev.adriangm.investigator.domain.Investigation.Status;
import dev.adriangm.investigator.domain.InvestigationWindow;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DomainTest {
  @Test
  void approvalBindsExactContentAndRepository() {
    var item = InvestigationFixtures.completed().investigation();
    String old = item.draft().hash();
    item.editDraft("Other title", "Other evidence", 1);
    assertThrows(IllegalStateException.class, () -> item.approve(1, old, "approval-1"));
    item.approve(2, item.draft().hash(), "approval-2");
    assertEquals(Status.PUBLISH_QUEUED, item.status());
    assertThrows(IllegalStateException.class, () -> item.editDraft("Changed", "Changed", 2));
  }

  @Test
  void repeatedApprovalIsIdempotentAndDifferentKeyConflicts() {
    var item = InvestigationFixtures.completed().investigation();
    item.approve(1, item.draft().hash(), "approval");
    item.approve(1, item.draft().hash(), "approval");
    assertThrows(
        IllegalStateException.class, () -> item.approve(1, item.draft().hash(), "different"));
  }

  @Test
  void lifecycleCannotBeSkipped() {
    var item = InvestigationFixtures.fresh().investigation();
    assertThrows(
        IllegalStateException.class, () -> item.complete(Diagnosis.empty(), "Title", "Body"));
    assertThrows(IllegalStateException.class, () -> item.requestClarification("Which release?"));
    assertThrows(
        IllegalStateException.class,
        () -> item.publicationConfirmed("https://github.com/example/orders-demo/issues/1"));
    assertEquals(Status.QUEUED, item.status());
  }

  @Test
  void evidenceAndDiagnosisCollectionsAreImmutable() {
    var item = InvestigationFixtures.completed().investigation();
    assertThrows(UnsupportedOperationException.class, () -> item.evidence().clear());
    assertThrows(UnsupportedOperationException.class, () -> item.diagnosis().facts().clear());
  }

  @Test
  void rejectedCompletionLeavesStateAndDraftUnchanged() {
    var item = InvestigationFixtures.running().investigation();
    var invalid =
        new Diagnosis(List.of("Invented [E99]"), List.of(), List.of(), List.of(), List.of("E99"));
    assertThrows(IllegalArgumentException.class, () -> item.complete(invalid, "Title", "Body"));
    assertEquals(Status.RUNNING, item.status());
    assertNull(item.draft());
  }

  @Test
  void invalidWindowsAreRejected() {
    var start = Instant.parse("2026-10-26T10:00:00Z");
    assertThrows(IllegalArgumentException.class, () -> new InvestigationWindow(start, start));
    assertThrows(
        IllegalArgumentException.class,
        () -> new InvestigationWindow(start, start.plusSeconds(91 * 86400L)));
  }

  @Test
  void technicalConversationIsNotPartOfTheDomainSnapshot() {
    var work = InvestigationFixtures.running();
    var message = new java.util.LinkedHashMap<String, Object>();
    message.put("role", "user");
    message.put("content", "Original");
    work.appendMessage(message);
    message.put("content", "Altered");
    assertEquals("Original", work.messages().getFirst().get("content"));
    assertThrows(
        UnsupportedOperationException.class,
        () -> work.messages().getFirst().put("content", "Altered"));
  }
}
