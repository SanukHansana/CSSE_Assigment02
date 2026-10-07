# Registration and officer approval

This guide replaces the earlier Citizen-only registration rule and 12-character minimum. The minimum was an implementation choice, not a user-specified assignment requirement. Passwords must be nonblank; bcrypt still limits them to 72 UTF-8 bytes. Passwords remain hashed, never returned or logged.

Registration UI offers Citizen, Community Volunteer, Duty Officer and DMC Officer. POST `/api/dmc/auth/register` accepts email, displayName, password and optional requestedRole (defaults to CITIZEN). CITIZEN/COMMUNITY_VOLUNTEER are immediately APPROVED. Officer accounts are PENDING and can log in only to see status. Their effective roles are empty until approved, even though their requested role is retained in MongoDB. The backend checks current database approval and roles on every protected request. Rejection keeps officer access blocked. Existing accounts without approvalStatus are grandfathered as approved, preserving previous demos; this change does not retroactively revoke them.

New request example:
```json
{"email":"officer@example.com","displayName":"Duty Officer Applicant","password":"demo123","requestedRole":"DUTY_OFFICER"}
```

## Admin setup through MongoDB only

No public request can register ADMIN, and no app/API can promote another user to ADMIN. No admin was seeded and no live database changes were made.

For the prototype, register a separate ordinary account with the admin's chosen email/password. In Atlas, find only that account in the configured database's `users` collection, and manually set:
```json
{"roles":["ADMIN"],"requestedRole":"ADMIN","approvalStatus":"APPROVED","enabled":true}
```
Edit these fields on the existing document; do not replace the complete document or edit passwordHash. This manual MongoDB change creates its Admin identity. Do not use your staff/citizen account for admin promotion.

If the users collection has the validator installed by migration 002, first apply `migrations/003-account-approval.js` against your intended development database, using the existing migration workflow. It adds ADMIN to allowed role values and changes no accounts. It has not been applied automatically. Without this update the old validator rejects ADMIN. Existing stored accounts need no migration of their data.

Restart backend/frontend. Home → Admin verification (or `/admin`) → sign in with the manually configured Admin account. Choose PENDING → Refresh Applications → Review Application → enter verification note → Confirm Approval / Confirm Rejection. Approval grants only the requested Duty/DMC Officer role, never ADMIN. Conditional database updates prevent conflicting admin decisions. The reviewer/time/note are stored. The applicant selects Refresh Approval Status or signs in again to see granted access; the existing token also picks up current server-side roles. Expired tokens require login again.

API: GET `/api/dmc/admin/applications?status=PENDING&page=0&size=20`; POST `/api/dmc/admin/applications/{id}/decision` with `{"approved":true,"note":"Identity and officer eligibility checked"}`. Both require current ADMIN authority. No password hashes or internal credentials are returned.

The Admin decides eligibility manually; automated identity verification, password reset/email verification and reapplying after rejection are not implemented. Register applicants before using the review list; legacy staff accounts were never applicants and do not appear there unless configured through MongoDB. This implementation does not modify backend/.env or any live account.

Validation follows the user's existing preference to skip automated tests: backend source/test compilation, frontend type/lint, and web build only. Suggested manual commit: `feat: add role registration and admin officer approval`.
