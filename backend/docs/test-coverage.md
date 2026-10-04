# Test scope and coverage

Measured separately on 14 October 2026:

| Application | Passing tests | Line coverage | Branch coverage |
| --- | ---: | ---: | ---: |
| Entire backend | 211 | 96.82% | 82.17% |
| Entire frontend source tree | 126 | 92.38% | 88.02% |

The build gates require at least **81% line coverage and 81% branch coverage** in each application. The frontend additionally gates statement and function coverage at 81% (measured 91.12% and 87.11%). These are aggregate application thresholds, not a guarantee that every individual file reaches 81%. No business-feature packages are excluded. Interfaces/type-only files contain no executable lines.

Backend: run `./mvnw clean verify` in `backend`. Report: `target/site/jacoco/index.html`.
Frontend: run `npm run test:coverage` in `frontend`. Report: `coverage/lcov-report/index.html`.
Frontend quality checks: `npm run check`; web build: `npx expo export --platform web`.

## Meaningful checks

- Ground reports: drafts, private evidence, submission, review assignment, checklist decisions, version conflicts, validation, authorization, persistence mapping and failure responses.
- Community support: Citizen/Volunteer role and ownership boundaries; full and partial receipt; overcommit prevention; decline/withdraw/cancel; terminal states; duplicate IDs and changed-input rejection; stale versions; failed persistence; malformed/invalid HTTP requests; safe projections.
- Relief: stock deduction, shelter occupancy bounds, duplicate coordination, unavailable resources, atomic commit and rollback behavior.
- Warnings: create/edit, selected channels, simulated delivery, failed-channel retry, duplicate broadcast, cancellation, expiry and stale edits.
- Rescue: dispatch, legal progress transitions, valid/invalid coordinates, team availability, transaction rollback, cancellation, completion prerequisites and closure.
- Email: mocked Brevo HTTP, private per-recipient messages, escaped content, UUID idempotency, provider acknowledgements/rejections/timeouts, persisted send claims, duplicate suppression, retry blocking and officer decision notifications.
- Accounts: registration roles, password hashing, pending officer applications, Admin approval/rejection, concurrent review and protected endpoints.
- Mobile layouts: phone/desktop viewport behavior, role tabs, account menu/logout, reporting-flow disclosure, non-admin screen rendering and retained Admin desktop navigation.
- Frontend interactions: login/registration, role navigation/logout, forms, review/confirmation steps, action payloads/versions, expired sessions, API errors, map selection and invalid messages, upload boundaries, abort/timeout handling and route rendering.

Tests assert persisted state, quantities/statuses, safe responses, authorization, request bodies/versions, idempotency and rollback. They do not rely on snapshots alone. Backend tests mock persistence boundaries; frontend tests run React Native Web screens in jsdom with mocked APIs/device services and Leaflet. Native WebView message handling is unit tested. These results do not establish live Atlas transactions, real browser map/CDN availability, native-device behavior, or real delivery/dispatch. Verify those separately with a manual smoke check. Warning delivery remains explicitly simulated.

Earlier coverage figures limited to ground reporting are superseded by these whole-application measurements.
