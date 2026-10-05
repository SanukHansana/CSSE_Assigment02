# MongoDB migration 001

The project previously had no migration runner or report collection. This versioned, explicit mongosh script creates `hazard_reports` (or updates its validator) and creates named indexes. It is safe to rerun against a compatible collection. It does not run on application startup, rewrite records, or access the application's private configuration files.

Apply to a development database using an already securely configured connection:

```sh
mongosh "$MONGODB_URI" --file migrations/001-ground-hazard-reports.js
```

Run from `backend`. The URI must include the intended database. Do not put credentials into source files or shell history. The script requires collection/index administration privileges. Inspect existing data before applying to an existing collection: index creation fails on duplicate references, and installing a validator does not repair or retrospectively validate existing documents. An interrupted run can be rerun; script execution is not a multi-command transaction. No migration was applied to the user's database during Stage 1.

The database validator enforces core document shapes and final-submission/decision requirements. Java constructors and the persistence callback additionally enforce finite coordinates, trusted ownership consistency, legal timeline order, and full aggregate invariants. Raw database writes bypass Java and optimistic locking; application code must use the aggregate repository's acknowledged `save` operation and retain its returned version. Do not use unversioned bulk updates for report decisions.

`schemaVersion` identifies document format; Spring Data's separate `@Version Long version` protects concurrent writes. Embedded review, verification, and history let a later service save a decision and final status atomically in one document, without requiring multi-document MongoDB transactions. Photo binaries will live outside this document; Stage 2 must choose a durable private storage adapter and cleanup strategy.

Reference/index changes should be made in a new numbered migration, preserving this script. Idempotency fields/indexes belong to Stage 4; this migration does not implement duplicate-safe offline submission.
