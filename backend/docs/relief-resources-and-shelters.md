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
