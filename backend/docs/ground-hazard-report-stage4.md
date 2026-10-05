# Ground hazard reports — Stage 4

## Completed local-report synchronization

`POST /api/dmc/ground-reports/sync` requires a Citizen/Community Volunteer bearer token and `Idempotency-Key` (1–128 letters, digits, dots, underscores, or hyphens). Multipart parts: `report` with application/json and `file` with image/jpeg or image/png.

Report JSON requires hazardType, nonblank description <=500 code units and location (both coordinates). Optional source defaults WEB_PORTAL; clientCapturedAt, location.capturedAt, and photoCapturedAt preserve observation/GPS/photo chronology. Source is client-declared metadata, never proof of identity. The same photo validation/5 MiB/20 megapixel limits apply as Stage 2.

Example JSON part:

```json
{"hazardType":"FLOODING","description":"Road flooded","location":{"latitude":6.9271,"longitude":79.8612},"source":"MOBILE_APP","clientCapturedAt":"2026-09-04T05:00:00Z"}
```

The endpoint creates a new complete report atomically at SUBMITTED; it does not update an existing server draft. A server draft continues to use Stage 2 endpoints. All responses are 200 with the original report identity and its current persisted status/version. A retry may return UNDER_REVIEW/VERIFIED/REJECTED if the original report has since progressed.

The authenticated subject and submission key produce a deterministic SHA-256 report ID. MongoDB's existing unique _id index resolves competing inserts without a new index or external migration. A canonical payload digest binds hazard type, exact description, normalized source, coordinates, area label, millisecond capture times, original filename, declared content type, and photo bytes. Keep those values and the key unchanged on retries. Reusing a key with different data returns 409 IDEMPOTENCY_KEY_REUSED. Different reporters may reuse the same key independently.

Only complete data with accepted evidence is saved. Successful retries do not add photos, report records or history events. A duplicate insert race returns the winning record and discards the losing unattached photo. Unknown database write outcomes retain photo files until retry resolves the persisted state. Duplicate prevention depends on the report record being retained; this stage adds no report-deletion endpoint. Orphan cleanup remains explicit/offline as in Stage 2.

## Verified official assessment evidence

DMC_OFFICER bearer tokens may call:

- GET `/api/dmc/ground-reports/verified-evidence?page=0&size=20&hazardType=FLOODING`: returns an array, newest submitted first then ID; optional hazardType; page >=0, size 1–100.
- GET `/api/dmc/ground-reports/verified-evidence/{id}`: verified detail, decision, timeline and safe evidence URLs.
- GET `/api/dmc/ground-reports/{id}/assessment/photo` and `/download`: authorized original photo.

Queries select VERIFIED status and matching verification decision; the domain invariant further excludes malformed/ineligible records. List pagination is over database candidates, so a page can be shorter if inconsistent legacy records are filtered; no total-count claim is returned. DRAFT, SUBMITTED, UNDER_REVIEW and REJECTED cannot be accessed through this boundary. Citizen and Duty Officer roles alone do not grant official assessment access.

No hazard assessment or warning is created or modified. No relatedAssessmentReference is fabricated. Review routes remain for Duty Officers; owned report routes remain for reporters. All projections omit private storage keys, hashes, and sync digests; no-store responses require authenticated photo fetching.

## Errors, verification and limits

400 invalid fields/key/missing parts; 401/403 authentication/role failures; 404 unavailable verified evidence; 409 conflicting key payload; 413 oversized upload; 422 invalid image; 503 database/storage failure. Unknown write outcomes must be resolved by refreshing/retrying the same key after connectivity returns.

Run `./mvnw test`. Tests cover actual image storage for synchronization, capture preservation, duplicate retries, key conflicts, subject isolation, simulated competing insert/save failure, evidence eligibility, role boundaries and HTTP validation. MongoDB repository race outcomes are simulated, not proven against a live database. No external database changes, migrations, or frontend changes were performed.

Local device drafts/photos, GPS capture, network detection, automatic retries and backoff remain frontend work. Backend synchronization support does not mean the mobile app already works offline. Stage 5 will measure coverage and complete development fixtures/documentation.
