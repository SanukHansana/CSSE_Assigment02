# Ground hazard reports — Stage 3 officer review

Stage 3 adds Duty Officer review only. Existing JWT authentication and database roles are reused. No account privilege endpoint, frontend, warning escalation, synchronization, or assessment module is added.

## Endpoints

Base: `/api/dmc/ground-reports`. All routes below require an enabled DUTY_OFFICER account and bearer authentication.

| Method | Path | Behavior |
| --- | --- | --- |
| GET | `/review-queue?q=road&status=SUBMITTED&hazardType=FLOODING&page=0&size=20` | Searchable filtered queue; default SUBMITTED; oldest submission first, then ID |
| GET | `/{id}/review/details` | Read-only report detail including checklist, decision, history, and safe evidence links |
| GET | `/{id}/review/photo` | Authorized original image bytes |
| GET | `/{id}/review/photo/download` | Authorized image attachment |
| POST | `/{id}/review/start` | Explicitly claim a submitted report and record server review time |
| PATCH | `/{id}/review` | Save the assigned officer's checklist and comments without deciding |
| POST | `/{id}/decision` | Save VERIFIED or REJECTED, working review data, verification record, and history in one document write |

Officer detail/evidence routes are separate from Stage 2 owner-only routes. Drafts remain private: officer reads return 404 and queue requests for DRAFT return 400. Any Duty Officer can read non-draft reports; only the officer assigned at review start may modify or decide. Review reassignment is outside this stage.

Queue filters permit SUBMITTED, UNDER_REVIEW, VERIFIED, or REJECTED. Search covers reference, reporter display name, and description, case-insensitively. Search text is treated literally and limited to 100 characters; it cannot inject regex patterns. Page >= 0, size 1–100. Count and list are independent reads, so totals can change during concurrent queue activity. No new database index or external migration was applied; reuse Stage 1 indexes and validate performance on the intended development dataset.

## Requests

Start review:

```json
{"expectedVersion": 3}
```

Save the current checklist/comments:

```json
{
  "expectedVersion": 4,
  "checklist": {
    "descriptionSufficientlyDetailed": true,
    "photoRelevant": true,
    "gpsCorrespondsToArea": true,
    "reportingTimeReasonable": true
  },
  "comments": "Photo and GPS location are consistent."
}
```

Checklist fields use true/false/null for passed/failed/unassessed. PATCH replaces checklist/comments; null comments clears them. Comments are limited to 2000 UTF-16 code units.

Verify using saved working data:

```json
{"expectedVersion": 5, "decision": "VERIFIED"}
```

A decision can also contain checklist and comments to save directly from the review screen in the same atomic document update. Omitted/null decision checklist/comments preserves the saved review values. Verification requires all four checklist values true, following the already implemented Stage 1 domain policy. A VERIFIED decision must omit rejectionReason.

Reject:

```json
{"expectedVersion": 5, "decision": "REJECTED", "rejectionReason": "Photo depicts a different location."}
```

Rejection requires a nonblank reason up to 2000 code units. Checks may be false or unassessed. All identities, references, lifecycle events, and decision timestamps are server-derived. Caller-supplied officer identity cannot replace the authenticated account.

## State, concurrency and persistence

Opening details never changes status. Only explicit review/start transitions SUBMITTED to UNDER_REVIEW. A stale expectedVersion, another assigned reviewer, an invalid review state, or a MongoDB optimistic-lock failure returns 409. Reload on conflict; decisions are never silently retried. VERIFIED and REJECTED remain terminal.

Status, embedded verification, checklist/comments, and append-only history save together in the existing versioned HazardReport document. MongoDB single-document atomicity avoids a separate multi-document transaction. A save failure returns 503 with no successful decision body; the modified in-memory object is discarded. If a database write outcome is unknown, refresh the persisted report when connectivity recovers rather than assuming the write failed or acknowledging success.

400: malformed/invalid request, missing required fields, invalid pagination, oversized comments/reason. 401/403: authentication/role failure. 404: unknown report or private draft. 409: conflicting version, competing reviewer, or invalid state. 422 REVIEW_VALIDATION_FAILED: unpassed verification checks or invalid/missing rejection reason, with fieldErrors. 503 REPORTS_UNAVAILABLE/EVIDENCE_UNAVAILABLE: backend dependency failure.

Safe report projections now include review {officerDisplayName, startedAt, checklist, comments} and verification {reference, officerDisplayName, decision, comments, rejectionReason, checklist, decidedAt}. Reporter-owned reads include the same decision/history data. Officer photo URLs point to review/photo. Responses use no-store and exclude internal identity references, storage keys, and hashes. No fabricated related-assessment URL is returned. Verified assessment endpoints remain Stage 4; rejection never triggers warning changes.

## Verification and remaining work

`./mvnw test` covers the existing stages plus officer access, read-only detail, draft privacy, queue filters/literal search/order, review claiming, checklist persistence, both decisions, mandatory rejection reasons, verification policy, terminal states, stale/competing writes and save failure preservation. HTTP tests exercise the real security/controller/service path with repository mocks and stubbed queue operations. They do not claim live MongoDB concurrency/durability or frontend integration. No external database data was changed.

Stage 4 remains duplicate-safe offline synchronization and verified assessment evidence access. Stage 5 remains final coverage measurement, fixtures, and integration documentation. Commit Stage 3 manually before continuing.
