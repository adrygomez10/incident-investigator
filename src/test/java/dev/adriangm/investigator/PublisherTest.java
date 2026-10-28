package dev.adriangm.investigator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import dev.adriangm.investigator.application.InvestigationWork;
import dev.adriangm.investigator.application.Ports.Issues;
import dev.adriangm.investigator.application.Publisher;
import dev.adriangm.investigator.domain.Investigation.ApprovedIssue;
import dev.adriangm.investigator.domain.Investigation.Status;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PublisherTest {
  static InvestigationWork publishing() {
    var work = InvestigationFixtures.completed();
    var item = work.investigation();
    item.approve(1, item.draft().hash(), "approval");
    item.claimPendingWork();
    return work;
  }

  @Test
  void uncertainWriteOnlyReconciles() {
    var work = publishing();
    var calls = new AtomicInteger();
    var publisher =
        new Publisher(
            new Issues() {
              @Override
              public String create(ApprovedIssue issue) {
                calls.incrementAndGet();
                throw new RuntimeException("Response lost");
              }

              @Override
              public Optional<String> reconcile(ApprovedIssue issue) {
                return Optional.of("https://github.com/example/orders-demo/issues/1");
              }
            },
            new MemoryStore());
    publisher.publish(work);
    assertEquals(Status.PUBLISH_UNKNOWN, work.investigation().status());
    publisher.reconcile(work);
    publisher.reconcile(work);
    assertEquals(Status.PUBLISHED, work.investigation().status());
    assertEquals(1, calls.get());
  }

  @Test
  void unknownReceiptDoesNotAuthorizeRetryOrEdit() {
    var work = publishing();
    work.investigation().publicationUncertain();
    var publisher =
        new Publisher(
            new Issues() {
              @Override
              public String create(ApprovedIssue issue) {
                fail("Must never create");
                return "";
              }

              @Override
              public Optional<String> reconcile(ApprovedIssue issue) {
                return Optional.empty();
              }
            },
            new MemoryStore());
    publisher.reconcile(work);
    assertEquals(Status.PUBLISH_UNKNOWN, work.investigation().status());
    assertThrows(
        IllegalStateException.class, () -> work.investigation().editDraft("New", "New", 1));
  }
}
