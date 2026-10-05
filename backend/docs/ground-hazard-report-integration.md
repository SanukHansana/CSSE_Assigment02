# Ground hazard reports — completed backend and frontend integration

## Scope and validation result

The five backend stages implement only Submit and Verify Ground Hazard Reports. JWT authentication is an existing supporting prerequisite. No disaster broadcasting, rescue coordination, relief/shelter operations, automatic risk scoring, or new frontend screens were implemented.

Final Stage 5 verification: 129 tests passed, no failures/errors; Maven executable JAR packaging succeeded; JaCoCo 0.8.14 coverage checks passed. Hazard reporting LINE coverage is 98.05% (655/668), BRANCH 76.76% (360/469), INSTRUCTION 96.63% (4422/4576). Coverage measures `com.dmc.backend.models.hazard` and `com.dmc.backend.reporting`, including their models, HTTP handlers, DTO projections, and error/storage/synchronization code. Authentication, generic application/configuration and test-only fixture generation are outside that selected-use-case denominator. No reporting classes were individually excluded to raise the result.

An automated 80% line-coverage minimum runs during `verify`. Coverage is execution evidence, not proof that 98.05% of requirements are correct. Branch coverage is below 80%; the assignment's functionality expectation should not be represented as a branch-coverage result. Tests cover positive, negative, ownership/role, concurrency-conflict, upload, persistence-failure, and delayed retry behavior.

Run from backend:

```sh
./mvnw clean verify
```

Open `target/site/jacoco/index.html` for the coverage report; XML/CSV are alongside it. The built application is `target/backend-0.0.1-SNAPSHOT.jar`. These generated outputs are ignored by Git. JaCoCo version was chosen for Java 25 support: https://www.jacoco.org/jacoco/trunk/doc/changes.html

## Screenshot actions mapped to the API

| Screen/action | Backend operation | Frontend responsibility |
| --- | --- | --- |
| Citizen form: hazard/description/reporting info | POST `/api/dmc/ground-reports/drafts`; PATCH `/{id}/draft` | Form state/validation, 500-code-unit counter, identity display |
| Save as Draft | Draft endpoints while online | Save drafts/photos locally when offline; incomplete GPS/photo must not discard entered data |
| Browse/replace/remove photo | PUT/DELETE `/{id}/photo`; PUT multipart file; both use quoted If-Match version | Picker/camera, preview, JPEG/PNG limits, authenticated fetch |
| Update Location/map | Validated coordinate/location fields in draft request | GPS permission, capture, map display; retain previous location on update failure |
| Review & Submit Report | POST `/{id}/submit` with expectedVersion | Show preview and confirmation, display structured errors, retain draft on failure |
| My Reports | GET `/mine` and `/{id}` | List/detail UI and current saved versions |
| Offline reporting banner | POST `/sync` accepts a complete queued local report plus file and Idempotency-Key | Durable device queue, capture timestamps, fixed payload/key across retries, connectivity detection/backoff |
| Officer queue/search | GET `/review-queue` | Search/status/hazard filters, pagination, select previous/next within loaded results |
| Officer report detail and full photo/download | GET `/{id}/review/details`, `/{id}/review/photo` and `/download` | Review screen, map/photo rendering, read-only opening |
| Start reviewing | POST `/{id}/review/start` | Explicit claim; handle another officer's claim/version conflict |
| Credibility checklist/comments | PATCH `/{id}/review` | Four tri-state checks; save working review |
| Verify/Reject | POST `/{id}/decision` | Confirmation, required rejection reason; show success only after saved response |
| Verified/rejected detail/history | Owner detail or officer detail with review/verification/history fields | Status banner, officer name/time, rejection reason and timeline |
| Official supporting evidence | DMC Officer GET `/verified-evidence` or `/verified-evidence/{id}` | Assessment consumer lists/maps evidence using returned protected photo links |
| View Related Hazard Assessment | No assessment module exists | Only add a link when another module provides a real authorized assessment reference |
| Potential High Risk indicator | No automatic priority/risk feature | Remains a design decision; any future triage must be explicitly justified |

All paths after the first row are relative to `/api/dmc/ground-reports`. Reporter, officer-review and DMC-evidence views have separate protected photo routes. Use the URL returned by the response and bearer authentication. A plain image URL without authorization returns 401. Respect no-store responses.

Draft PATCH replaces hazardType/description/location; omitted/null fields clear those optional values. Decision checklist/comments can be included with the decision and save atomically; omitted/null values preserve the existing review values. Use the version returned by each successful mutation in the next request. On 409, reload rather than overwrite or silently retry stale updates.

Verified evidence pagination returns an array and no total; queue/mine return a page object. Only VERIFIED records with a consistent persisted decision may cross the official evidence boundary. A successful synchronization retry returns the same report identity with its current status, which may have progressed beyond SUBMITTED.

## Original report requirements not fully visible in supplied screenshots

Community Volunteer reporting is implemented. Officer review queue endpoints are implemented. Rejected report details/reasons and exclusion from official evidence are implemented. Incomplete drafts retain missing photo/location fields; the frontend must preserve those drafts locally on capture failures. Failed decision persistence returns a failure response and never acknowledges a false verified state. Local queue storage/automatic synchronization still need frontend implementation.

## Justified additions for the revised assignment design

1. QUEUED_FOR_SYNC is client-local; server SUBMITTED means a complete report has been saved.
2. Synchronization keys/payload digests prevent duplicate reports and reject changed-payload key reuse.
3. Expected versions and assigned reviewers prevent conflicting decisions/edits.
4. Verification, status and history save together in one versioned document.
5. Private evidence URLs enforce authorization; file types/content/dimensions are validated.

Carry these decisions into the revised use case scenarios, sequence/class diagrams and UI feedback. All four checks passing is the existing verification policy; rejection does not require checks to pass. No automatic warning escalation is implemented.

## Limits to demonstrate honestly

No live MongoDB/Atlas migration or durability/concurrency experiment was run. HTTP/service tests mock repositories, BSON mapping tests use the real mapper, and photo/sync tests use actual local file storage and image decoding. Live end-to-end testing requires the intended database, authorized demo accounts and frontend integration.

MongoDB documents and local photo files are separate resources. Unknown write outcomes retain files; retries resolve saved report identity. Replaced/orphaned photos need deliberate cleanup. Local evidence storage needs persistence/backup, and multiple backend instances need a shared storage adapter. No automated cleanup or cross-resource transaction is claimed.

## Per-stage API details

- Stage 2: `ground-hazard-report-stage2.md` — drafts, photos, submission and ownership.
- Stage 3: `ground-hazard-report-stage3.md` — review, checklist and decisions.
- Stage 4: `ground-hazard-report-stage4.md` — canonical retry keys and verified evidence.
- Demo data: `../dev-fixtures/README.md`.
