# Ground hazard reports — Stage 2

Stage 2 implements reporter-owned drafts, private photo evidence, submission, and owned reads. Reuses custom JWT authentication; no frontend, officer review endpoints, synchronization endpoint, warnings, rescue, or shelter features are added.

## Identity and authorization

Send `Authorization: Bearer <access token>`. Current enabled database roles govern access. CITIZEN and COMMUNITY_VOLUNTEER may use these endpoints; other roles alone receive 403. Reads/mutations query report ID plus authenticated owner ID, returning 404 for unknown or unowned reports. Client identity, status, reference, and server event timestamps never determine identity or lifecycle. Volunteer reporter type is resolved from the account; where an account has both reporter roles, COMMUNITY_VOLUNTEER takes precedence. Registration still creates only citizens: no privilege-granting endpoint was added.

Source is optional client-declared metadata (`WEB_PORTAL` by default, or `MOBILE_APP`); it is not trusted proof of which client was used. No credentials, internal account IDs, private storage keys, or photo hashes appear in report responses. Reports and evidence use `Cache-Control: no-store`.

## Endpoints

Base: `/api/dmc/ground-reports`.

| Method | Path | Behavior |
| --- | --- | --- |
| POST | `/drafts` | Create draft, 201, returns ID and persisted version |
| GET | `/mine?page=0&size=20&status=DRAFT` | Owned paginated reports; optional status; page >= 0, size 1–100 |
| GET | `/{id}` | Read-only owned detail and history; never starts review |
| PATCH | `/{id}/draft` | Replace hazardType/description/location while DRAFT; body expectedVersion required |
| PUT | `/{id}/photo` | Multipart file upload/replacement; quoted version in If-Match header |
| DELETE | `/{id}/photo` | Clear photo metadata while DRAFT; quoted version in If-Match header |
| GET | `/{id}/photo` | Authorized original JPEG/PNG inline bytes |
| GET | `/{id}/photo/download` | Same bytes as an attachment with safe filename |
| POST | `/{id}/submit` | Required-field and stored-evidence checks; atomic single-document submission/history save |

`/mine` returns `{items, page, size, totalElements, totalPages}` sorted by createdAt descending then ID ascending. Mutation responses include the persisted version; use that version in the next mutation. No reporting endpoint is public. Review and verified-evidence routes remain future stages, even though the original domain model contains review methods.

## Request examples

Create an incomplete draft:

```json
{
  "hazardType": "FLOODING",
  "description": "Water covers the road.",
  "location": {
    "latitude": 6.9271,
    "longitude": 79.8612,
    "areaLabel": "Colombo District, Western Province",
    "capturedAt": "2026-09-04T05:04:00Z"
  },
  "clientCapturedAt": "2026-09-04T05:00:00Z",
  "source": "WEB_PORTAL"
}
```

An empty object is valid for draft creation. The catalogue is FLOODING, RISING_RIVER_LEVEL, BLOCKED_ROAD, LANDSLIDE_CRACK. The description counter uses Java UTF-16 code units, matching JavaScript string.length. A supplied location requires both finite coordinates, latitude [-90,90] and longitude [-180,180]; zero is valid. Optional areaLabel is limited to 200 characters. Omit location when GPS is unavailable; do not invent coordinates.

PATCH uses a complete replacement of the three editable fields (not JSON Merge Patch). Omitted/null fields clear those optional values; photo and original clientCapturedAt are unchanged. For example:

```json
{
  "expectedVersion": 0,
  "hazardType": "FLOODING",
  "description": "Water now covers the bridge.",
  "location": {"latitude": 6.9271, "longitude": 79.8612}
}
```

Upload: multipart field `file`, optional ISO-8601 `capturedAt` form parameter, and header `If-Match: "1"`. The quotes are required; wildcards and weak validators are rejected. After upload, use the new version from the response.

Submit: `POST /{id}/submit` with `{"expectedVersion": 2}`. Submission requires hazard type, nonblank description up to 500 code units, valid location, and a stored photo with matching size and SHA-256. Submission locks draft editing and photo mutations. Failed field validation or missing evidence leaves the saved draft unchanged. A database failure does not return success; an unknown write outcome may require refreshing the report before retrying. Stage 4 will add duplicate-safe completed-report synchronization.

## Photo storage

Only actual decodable JPEG and PNG images are accepted. The declared media type must match decoded content. Files are limited to 5 MiB and 20 megapixels; the multipart request envelope is limited to 6 MiB. Image bytes are validated and stored under random server-generated keys; sanitized client names never select a filesystem path. No URL/static mapping exposes the directory. The frontend must fetch photo routes with its bearer token; a plain unauthenticated image URL will return 401.

Private prototype storage defaults to `backend/.data/hazard-evidence` when launched from backend. Override using `DMC_EVIDENCE_DIRECTORY`. The directory is ignored by Git; keep it persistent and back it up alongside MongoDB. Multi-instance deployment needs a shared persistent evidence adapter before production. Storage is created lazily; application startup does not create or validate the directory.

A new photo is stored before MongoDB metadata is acknowledged. An optimistic-lock failure discards the new unattached file. Ambiguous database failures retain it because the write may have succeeded. Replaced and removed files remain private until deliberate orphan cleanup; this avoids deleting evidence referenced by concurrent reads or unknown write outcomes. No automatic orphan-cleanup or storage/database cross-resource transaction is claimed. Do not purge these files blindly.

## Errors and concurrency

Responses contain code, message, and fieldErrors; internal paths and database details are excluded.

- 400 INVALID_REQUEST: malformed JSON, invalid fields/enums, missing version/file, invalid pagination or If-Match.
- 401: missing/invalid authentication or disabled account.
- 403: current account lacks a reporter role.
- 404 REPORT_NOT_FOUND or PHOTO_NOT_FOUND: unavailable owned resource.
- 409 REPORT_VERSION_CONFLICT: expected version differs or MongoDB optimistic save loses a race.
- 409 REPORT_NOT_EDITABLE: report has left DRAFT.
- 413 PHOTO_TOO_LARGE: upload exceeds configured limits.
- 422 REPORT_VALIDATION_FAILED: incomplete final submission, with all missing field names.
- 422 INVALID_PHOTO: empty, fake, unsupported, mismatched or excessively large image dimensions.
- 503 REPORTS_UNAVAILABLE or EVIDENCE_UNAVAILABLE: database or file storage failures.

All subsequent mutations require a version. Services check the loaded version and Spring Data @Version guards the final write. A 409 requires reload; never retry an outdated update silently.

## Requirements represented beyond the screenshots

Community-volunteer reporting is supported through existing roles. GPS/photo failures can retain an incomplete draft. Field errors identify missing evidence and location. The queued/submitted distinction is preserved: QUEUED_FOR_SYNC remains client-local, while SUBMITTED means the backend accepted the saved complete report. Local device storage, GPS capture, previews, retry scheduling, and automatic offline synchronization are not implemented by this backend stage.

The reporter sees its history with safe actor display names, photo metadata and protected URLs. Duty-officer review, rejection details, checklist persistence and verified-assessment integration belong to Stages 3–4. The screenshot's automated-looking priority label is not implemented; no automatic risk scoring or warning escalation is introduced.

## Verification

Run `./mvnw test` from backend. Stage 2 tests exercise actual image decoding/filesystem storage, controller/security behavior, owner scoping, incomplete drafts, submission, version conflicts, and failure preservation. MongoDB repositories are mocked in HTTP/service tests; BSON mapping is real. These tests do not prove a live Atlas migration, live database durability, or frontend integration. No external migration, account creation, or existing database data changes are performed by the tests.
