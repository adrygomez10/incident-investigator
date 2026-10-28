package dev.adriangm.investigator.adapter.in.web;

import dev.adriangm.investigator.application.InvestigationView;
import dev.adriangm.investigator.application.ManageInvestigations;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Maps the private HTTP investigation API to application commands and views. */
@RestController
@RequestMapping("/investigations")
public final class InvestigationController {
  private final ManageInvestigations investigations;

  /** Receives the application use cases used by the private HTTP API. */
  public InvestigationController(ManageInvestigations investigations) {
    this.investigations = investigations;
  }

  /** Accepts an operator request and returns the location of its persisted investigation. */
  @PostMapping
  public ResponseEntity<InvestigationView> create(
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody ManageInvestigations.Start request) {
    InvestigationView view = investigations.start(key, request);
    return ResponseEntity.accepted()
        .location(URI.create("/investigations/" + view.id()))
        .body(view);
  }

  /** Returns the current persisted investigation projection. */
  @GetMapping("/{id}")
  public InvestigationView get(@PathVariable UUID id) {
    return investigations.get(id);
  }

  /** Accepts a clarification against the version reviewed by the operator. */
  @PostMapping("/{id}/messages")
  public ResponseEntity<InvestigationView> answer(
      @PathVariable UUID id, @RequestBody ManageInvestigations.Answer request) {
    return ResponseEntity.accepted().body(investigations.answer(id, request));
  }

  /** Replaces draft content using the reviewed draft version. */
  @PutMapping("/{id}/draft")
  public InvestigationView edit(
      @PathVariable UUID id, @RequestBody ManageInvestigations.Edit request) {
    return investigations.edit(id, request);
  }

  /** Accepts approval of an exact draft without offering a write operation to the model. */
  @PostMapping("/{id}/issue")
  public ResponseEntity<InvestigationView> approve(
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody ManageInvestigations.Approval request) {
    return ResponseEntity.accepted().body(investigations.approve(id, key, request));
  }
}
