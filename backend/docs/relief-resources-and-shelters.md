# Relief resources and shelters — step 1 of 2

Git author for the manual commit: Arosha. No author settings or commits are changed automatically.

Simple MongoDB-backed staff inventory and shelter APIs. Requires an enabled `DMC_OFFICER` account and Bearer token; public registration still creates citizens. This uses the existing officer role for the prototype. Names such as Arosha are Git authors, not authorization rules. No accounts or roles are changed in a live database.

Base URL: `http://localhost:8080/api/dmc/relief`.

| Method | Path | Purpose |
| --- | --- | --- |
| GET / POST | `/resources` | Paginated inventory / create resource |
| GET / PUT | `/resources/{id}` | Detail / replace editable resource fields |
| GET / POST | `/shelters` | Paginated shelters / create shelter |
| GET / PUT | `/shelters/{id}` | Detail / replace editable shelter fields |

Lists support `?page=0&size=20` (size 1–100). Copy `id` and the actual `version` from responses. PUT requires `expectedVersion`; MongoDB version checks protect the final save against concurrent writers. Stock quantities are whole units. Shelter occupancy must fit capacity. FULL requires occupancy = capacity; OPEN requires free space; CLOSED may retain occupants.

Postman: POST `/resources`, raw JSON, Bearer token:

```json
{"name":"Drinking water","type":"WATER","location":"Colombo storage centre","availableQuantity":1000,"unit":"bottles"}
```

POST `/shelters`:

```json
{"name":"Community Hall Shelter","location":"Colombo District","capacity":200,"occupancy":35,"status":"OPEN"}
```

PUT uses the same complete JSON plus `"expectedVersion":0` (use your response's actual version). Stored last-updated officer and time are server-derived. New collections are created on first write; no seed/import or external migration is performed. Step 1 provides administrative stock/occupancy replacement; step 2 will add consistent allocation and occupancy-change workflows and history. No frontend for these modules or automatic dispatch is implemented in this step.

Automated tests are skipped at the user's request. Compilation only is checked; no runtime/database verification is claimed. Previous hazard reporting coverage percentages do not apply to this new module.

Manual commit: `feat: add relief inventory and shelter management`
Next step: resource allocations, occupancy changes, and history (Arosha's second commit).

## Step 2 — frontend and backend coordination

Open a resource/shelter from the frontend list to allocate supplies or record arrivals/departures, and view allocation/activity history. Actor/time comes from the server. Stored histories on older documents default to empty; no migration is required.

POST `/resources/{id}/allocations`: `{"requestId":"unique-operation-id","expectedVersion":0,"shelterId":"saved-shelter-id","quantity":20,"note":"Drinking water for residents"}`. Deducts stock and records shelter history in one MongoDB transaction. Requires Atlas or a replica set; standalone MongoDB cannot run this allocation workflow. Closed shelters reject allocations. A FULL shelter may still receive supplies.

POST `/shelters/{id}/occupancy`: `{"requestId":"unique-operation-id","expectedVersion":0,"change":5,"note":"Five new arrivals"}`. Use negative change for departures. Enforces capacity, prevents admissions to closed shelters, and derives OPEN/FULL while retaining CLOSED.

Retries with the same request ID and same payload return the saved record without repeating the operation; changed payload is rejected. The UI retains its pending ID after uncertain failures; do not change the form before retry. Reload to inspect history before a new operation. Allocation histories live inside resource/shelter documents and administrative edits preserve them. The step-1 PUT endpoints remain authorized full inventory/occupancy corrections and append activity history. No automatic distribution to citizens, procurement, or physical delivery integration is claimed.

No automated tests were run at user request. Compile/type/lint checks only; runtime/transaction behavior remains unverified. Manual commit (Git author Arosha): `feat: coordinate relief allocations and shelter occupancy`. Next: step 3 compose/preview warnings (frontend and backend); switch Git author to Anuja before that commit.
