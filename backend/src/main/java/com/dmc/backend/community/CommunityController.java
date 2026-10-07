package com.dmc.backend.community;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/dmc/community/needs")
@PreAuthorize("hasAnyRole('CITIZEN','COMMUNITY_VOLUNTEER')")
public class CommunityController {

  public record CreateRequest(
    @NotBlank @Size(max = 100) String item,
    @NotBlank @Size(max = 60) String category,
    @NotNull @Positive @Max(1000000) Long quantity,
    @NotBlank @Size(max = 30) String unit,
    @NotBlank @Size(max = 500) String description,
    @NotBlank @Size(max = 200) String area
  ) {}

  public record OfferRequest(
    @NotBlank @Size(max = 100) String requestId,
    @NotNull @PositiveOrZero Long expectedVersion,
    @NotNull @Positive @Max(1000000) Long quantity,
    @NotBlank @Size(max = 500) String note
  ) {}

  public record ActionRequest(
    @NotBlank @Size(max = 100) String requestId,
    @NotNull @PositiveOrZero Long expectedVersion,
    @NotBlank String action
  ) {}

  public record CancelRequest(
    @NotBlank @Size(max = 100) String requestId,
    @NotNull @PositiveOrZero Long expectedVersion
  ) {}

  private final CommunityService service;

  public CommunityController(CommunityService service) {
    this.service = service;
  }

  @PostMapping
  @PreAuthorize("hasRole('CITIZEN')")
  public ResponseEntity<CommunityService.NeedView> create(
    @AuthenticationPrincipal Jwt jwt,
    @Valid @RequestBody CreateRequest body
  ) {
    return ResponseEntity.status(HttpStatus.CREATED)
      .cacheControl(CacheControl.noStore())
      .body(
        service.project(
          service.create(jwt.getSubject(), body),
          jwt.getSubject()
        )
      );
  }

  @GetMapping
  public ResponseEntity<CommunityService.Page> list(
    @AuthenticationPrincipal Jwt jwt,
    @RequestParam(defaultValue = "mine") String view,
    @RequestParam(defaultValue = "0") int page,
    @RequestParam(defaultValue = "10") int size
  ) {
    return ResponseEntity.ok()
      .cacheControl(CacheControl.noStore())
      .body(service.list(jwt.getSubject(), view, page, size));
  }

  @GetMapping("/{id}")
  public ResponseEntity<CommunityService.NeedView> detail(
    @AuthenticationPrincipal Jwt jwt,
    @PathVariable String id
  ) {
    return response(service.detail(jwt.getSubject(), id));
  }

  @PostMapping("/{id}/offers")
  public ResponseEntity<CommunityService.NeedView> offer(
    @AuthenticationPrincipal Jwt jwt,
    @PathVariable String id,
    @Valid @RequestBody OfferRequest body
  ) {
    return response(service.offer(jwt.getSubject(), id, body));
  }

  @PostMapping("/{id}/offers/{offerId}/action")
  public ResponseEntity<CommunityService.NeedView> act(
    @AuthenticationPrincipal Jwt jwt,
    @PathVariable String id,
    @PathVariable String offerId,
    @Valid @RequestBody ActionRequest body
  ) {
    return response(service.act(jwt.getSubject(), id, offerId, body));
  }

  @PostMapping("/{id}/cancel")
  public ResponseEntity<CommunityService.NeedView> cancel(
    @AuthenticationPrincipal Jwt jwt,
    @PathVariable String id,
    @Valid @RequestBody CancelRequest body
  ) {
    return response(service.cancel(jwt.getSubject(), id, body));
  }

  private ResponseEntity<CommunityService.NeedView> response(
    CommunityService.NeedView value
  ) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
  }
}
