# Ground hazard reports: Stage 1 domain and proposed API

**Current implementation:** [Stage 2 endpoints and evidence storage](ground-hazard-report-stage2.md) now exist and reuse custom JWT authentication. The notes below preserve the original Stage 1 design and proposed later-stage contract; statements about missing reporting endpoints describe that earlier stage.

## Scope and reference decisions

Stage 1 adds domain models, persistence mapping, a save validation callback, a repository, a versioned MongoDB migration, and tests. **None of the reporting endpoints below exist yet.** `/api/dmc` retains its existing availability response. No frontend, authentication, warnings, risk scoring, assessment module, or offline retry scheduler is added.

Design references: the three supplied submission/review/verified screenshots and Assignment 1's “Submit & Verify Ground Hazard Report” scenario (pages 14–16 of the supplied PDF). The user's staged workflow takes precedence over document instructions. Specifically, GET detail will not start review even though the scenario describes opening a report as doing so. The screenshot's offline message describes future frontend work, not implemented backend functionality.

The repository has no user model, security configuration, authenticated principal integration, evidence storage convention, migration runner, or hazard-assessment model. `UserReference` is an opaque subject reference plus a historical display-name snapshot, **not** a new user/account module. Later services must populate it from the agreed existing authentication integration. Never deserialize caller-provided reporter IDs, officer IDs, roles, final statuses, references, or event timestamps as authoritative fields. Stage 2's protected endpoints need an agreed authentication integration first; introducing a new one requires separate authorization.

## Domain and persistence

`models/hazard/HazardReport` maps to one MongoDB document in `hazard_reports`. It embeds:

- Reporter identity and citizen/community-volunteer type; mobile-app/web-portal source.
- Nullable draft hazard type, description, location, and one photo, matching the single-photo screenshot.
- Photo ID, private storage key, original filename, media type, size, SHA-256 digest, uploader identity, server upload time, and optional client capture time. No image bytes or public URLs are stored in the report.
- Review assignment, start time, current credibility checklist and officer comments.
- A `ReportVerification` with its reference, officer snapshot, decision, comments, rejection reason where applicable, checklist snapshot, and server decision time.
- An append-only history of creation, draft edits, evidence changes, submission, review start/update, and decisions.
- `schemaVersion = 1` for the document format and a separate nullable `@Version Long version` for optimistic locking. New objects have no version until Spring Data saves them; persisted versions must be returned and used by later services.

The aggregate has controlled mutation methods and no public setters. Jakarta validation runs through a registered Spring Data save callback. Metadata constructors validate their inputs, and a cross-field invariant checks state, decision, and timeline consistency. The repository is internal and provides ownership-scoped reads plus status pagination; it does not itself perform HTTP authorization. Do not expose MongoDB documents as request/response DTOs: private storage keys and identity references need careful projection.

The initial hazard catalogue is `FLOODING`, `RISING_RIVER_LEVEL`, `BLOCKED_ROAD`, and `LANDSLIDE_CRACK`, drawn from the scenario examples. Only Flooding is visible in the collapsed screenshot dropdown; this is an explicit provisional catalogue, not a claim about its hidden options. Confirm the full list before final UI integration.

References use `HR-<UTC year>-<12 uppercase UUID-derived characters>` and `RV-<UTC year>-<12 uppercase UUID-derived characters>`. Unique indexes enforce persisted uniqueness. The screenshot's sequential numeric examples are presentation examples; this implementation does not allocate counters or assume a report count. Future services should handle the unlikely duplicate-key failure safely.

See [migration instructions](../migrations/README.md). The migration creates a validator and unique/reference/ownership/queue indexes. It was not applied to an external database. Domain and BSON mapping tests run without a database; they do not prove live index creation, durable writes, or concurrent database rejection.

## States and validation

```text
DRAFT -> SUBMITTED -> UNDER_REVIEW -> VERIFIED
                                  -> REJECTED
```

Only these transitions are legal; verified and rejected reports are terminal. No draft edit, evidence replacement/removal, or resubmission may modify a non-draft report. Invalid model operations leave its state and history unchanged. These domain methods prepare a change; success is only acknowledged to a client after a later service saves it durably.

`QUEUED_FOR_SYNC` is exclusively client-local. An unsent locally queued report is not a server submission. A device can hold a local draft without any server report ID. A previously synchronized draft may remain `DRAFT` on the server while its completed payload waits locally for submission. Stage 4 will add duplicate-safe synchronization; Stage 1 has no idempotency key or synchronization endpoint.

Drafts may omit hazard type, description, photo, and location. Any supplied description is limited to **500 Java UTF-16 code units**, matching JavaScript `string.length` for the frontend counter. Whitespace-only descriptions are allowed in drafts but not final submission. Supplied locations must contain a full finite WGS84 coordinate pair: latitude [-90, 90], longitude [-180, 180]; zero is valid. Drafts with no GPS omit `location`, rather than inventing coordinates.

Submission requires hazard type, nonblank description of at most 500 code units, photo metadata, and valid coordinates. Metadata alone does not establish valid image content or durable storage. Stage 2 must validate actual supported image bytes, agreed size limits, safe filenames, ownership, and successful storage before attaching the photo or acknowledging submission. Stage 1 accepts image media-type metadata without implementing an upload allowlist or binary-content inspection.

Credibility fields exactly match the review screenshot:

1. `descriptionSufficientlyDetailed`
2. `photoRelevant`
3. `gpsCorrespondsToArea`
4. `reportingTimeReasonable`

`null` means unassessed, `false` means assessed/failed, and `true` means assessed/passed. **Proposed policy:** verification requires all four to be true. Rejection can have failed or unassessed checks, and always requires a nonblank separate `rejectionReason`. This policy is encoded and tested in the model; confirm it when authorizing Stage 3. No automated credibility/risk score is calculated. Officer comments are preserved independently of the rejection reason.

The assigned review officer's subject reference must match the deciding identity. This is a consistency guard, not authorization: later application services must first establish that the authenticated user is a Duty Officer. Reassignment is outside the current workflow.

The screenshot's “Potential High Risk” indicator is not implemented in Stage 1. If added later, it must be manual officer triage metadata with provenance, never an automatic score or official warning-level update.

## Timestamp contract

All server event times come from a server-provided `Clock`, not HTTP fields. MongoDB stores `Instant` as UTC BSON dates at millisecond precision; returned DTOs must use timezone-aware ISO-8601 UTC strings such as `2026-09-04T05:05:00Z`. The frontend may display these in Asia/Colombo time. Capture timestamps are client assertions used for chronology/credibility, not trusted ordering of server events.

| Field | Origin / purpose |
| --- | --- |
| `clientCapturedAt` | Optional client observation/report capture time, preserved after sync |
| `location.capturedAt` | Optional client GPS capture time |
| `photo.capturedAt` | Optional client photo capture time |
| `createdAt` | Server receipt of initial draft |
| `photo.uploadedAt` | Server acceptance of stored photo |
| `submittedAt` | Server acceptance of complete final submission |
| `review.startedAt` | Server acceptance of explicit review-start action |
| `verification.decidedAt` | Server decision time, only exposed after successful persistence |
| `updatedAt`, `history[].occurredAt` | Server mutation/timeline times |

Capture values are retained independently, normalized only to MongoDB's millisecond precision; delayed synchronization does not replace them with receipt time. Server event order must not go backwards. Receipt, upload, capture, review, and decision have separate meanings even when a screenshot displays similar times.

## Proposed HTTP contract for subsequent stages

Base path: `/api/dmc/ground-reports`. Citizen and Community Volunteer access is restricted to their own reports/evidence, including drafts. Duty Officers access submitted/reviewed/decided reports for review. DMC Officer assessment access requires that this role exists in the agreed identity integration. Return 401 for absent authentication and 403 for disallowed role; use 404 for inaccessible owned resources to avoid disclosing their existence.

| Stage | Method / path relative to base | Behavior |
| --- | --- | --- |
| 2 | `POST /drafts` | Create an incomplete owned draft; identity/source/reference/time are server-resolved |
| 2 | `GET /mine?page=0&size=20&status=DRAFT` | Owned reports/drafts with stable createdAt/id ordering |
| 2 | `GET /{id}` | Authorized read-only detail, including timeline |
| 2 | `PATCH /{id}/draft` | Update draft fields only, using expected version |
| 2 | `PUT /{id}/photo` | Multipart upload/replacement while a draft; field `file` and optional `capturedAt` |
| 2 | `DELETE /{id}/photo` | Remove draft evidence, using expected version |
| 2 | `GET /{id}/photo` | Authorized inline viewing of original supported image |
| 2 | `GET /{id}/photo/download` | Authorized attachment download using sanitized original filename |
| 2 | `POST /{id}/submit` | Validate all final fields and save SUBMITTED + submission history |
| 3 | `GET /review-queue?q=HR-2026&status=SUBMITTED&hazardType=FLOODING&page=0&size=20` | Duty Officer queue with reference/reporter/description search and stable submittedAt/id ordering; allowlisted sorts, page-size cap 100 |
| 3 | `POST /{id}/review/start` | Explicit SUBMITTED -> UNDER_REVIEW with authenticated officer and server time |
| 3 | `PATCH /{id}/review` | Save checklist/comments for assigned officer without deciding |
| 3 | `POST /{id}/decision` | VERIFIED/REJECTED decision; save status, verification, history together |
| 4 | `POST /sync` | Multipart completed local report + evidence; scoped idempotency key and payload digest |
| 4 | `GET /verified-evidence` | Authorized assessment integration boundary; query only VERIFIED reports with a consistent persisted verification |
| 4 | `GET /verified-evidence/{id}` | Verified supporting detail, verification, timeline, and authorized photo links |

These are proposed route names, not deployed APIs. Static paths must precede/avoid ambiguity with `{id}`. Review queue search/filter details can be refined in Stage 3; no queue screenshot was supplied.

Example draft fields (no caller identity or server status):

```json
{
  "hazardType": "FLOODING",
  "description": "Water covers the access road.",
  "location": {
    "latitude": 6.9271,
    "longitude": 79.8612,
    "areaLabel": "Colombo District, Western Province",
    "capturedAt": "2026-09-04T05:04:00Z"
  },
  "clientCapturedAt": "2026-09-04T05:00:00Z"
}
```

Every mutation after creation will require `expectedVersion` (as a body field or a consistently documented header for multipart/delete operations) and compare it to the loaded report version. Stage 3 must convert stale versions and Spring Data optimistic-lock failures into **409 CONFLICT**. Do not bypass version protection with blind update operations or retry a conflicting decision silently. A failed save must not return the mutated in-memory object as a successful persisted decision; reload/discard it and retain the persisted UNDER_REVIEW state. The Stage 1 model provides the version field and single-document layout, not HTTP conflict handling or a tested durable decision service.

Proposed validation response (422; malformed JSON/enum/coordinates use 400):

```json
{
  "code": "REPORT_VALIDATION_FAILED",
  "message": "Complete the required fields before submitting.",
  "fieldErrors": [
    { "field": "photo", "code": "required", "message": "Attach a photo." },
    { "field": "location", "code": "required", "message": "Provide valid coordinates." }
  ]
}
```

Later stages must implement these structured errors through the established/new agreed exception-handling convention; existing code has no global error handler. Do not return internal stack traces or storage keys. Failed validation/submission must retain the existing draft and evidence.

## Assessment and synchronization boundaries

Only a durably saved VERIFIED report with a matching verification may appear in official assessment evidence. DRAFT, SUBMITTED, UNDER_REVIEW, and REJECTED are excluded. `isEligibleForAssessment()` is a domain predicate; no public evidence endpoint is implemented yet. There is no assessment collection/model in this project, so future responses must omit `relatedAssessmentReference` (or return null) until an actual authorized integration supplies an existing reference. Never fabricate the link shown in the verified screenshot or automatically update official warnings.

Stage 4 will scope client-generated idempotency keys to the authenticated reporter, persist a canonical payload/evidence digest, return the original result for identical retries, and reject different payloads using the same key with 409. Required evidence and report data must be durably accepted before success. Frontend local draft/photo storage, GPS capture, connectivity detection, and retry scheduling remain frontend work. Backend-only tests cannot demonstrate those capabilities.

## Stage 1 verification and remaining work

Run `./mvnw test` from `backend`. Tests cover both reporter types, incomplete drafts, all legal/illegal state pairs, required final fields, description limits, finite/boundary coordinates, photo metadata and ownership consistency, capture-time retention, decisions/rejection reasons/checklist policy, terminal states, timeline and immutable history, actual BSON conversion, version mapping, and the persistence validation callback. The existing `/api/dmc` web test remains unchanged.

No live database migration, binary upload/download, HTTP ownership enforcement, durable-save failure/concurrency test, reference collision handling service, sync/idempotency flow, or frontend integration is claimed. MongoDB's document-size limit also bounds embedded history; later services should enforce reasonable comments/payload limits and a retention policy before production growth. Stage 5 will measure coverage; Stage 1 makes no coverage-percentage claim.

## Authentication prerequisite added after Stage 1

A separately authorized [custom JWT module](custom-authentication.md) now supplies a `users` collection, authenticated subject IDs, current database roles, and safe user projections. The absence-of-authentication notes above describe the original Stage 1 inspection. Stage 2 should reuse this module rather than introduce another identity store. No reporting endpoints have been added by the authentication prerequisite.
