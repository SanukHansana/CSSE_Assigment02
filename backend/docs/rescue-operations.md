# Rescue operations — step 5 of 6 (Sanuk)

Frontend/backend incident and team management uses existing DMC_OFFICER role. Open Rescue Operations from Home. Save/edit incident type, priority, description, address/GPS and people requiring assistance; create/edit team name, specialization, last-confirmed location and AVAILABLE/UNAVAILABLE status. Paginated list/detail, private officer access, version protection, server actor/time and incident history. No live GPS, automatic proximity ranking or travel-time estimate is claimed; map links show stored coordinates.

Base `/api/dmc/rescue`. GET/POST `/incidents` and `/teams`; GET/PUT `/{collection}/{id}`. PUT requires the latest `expectedVersion`. New incidents start OPEN. Team deployments cannot be set manually through this endpoint; active assigned teams cannot be edited here. Types: FLOOD_RESCUE, MEDICAL_RESCUE, STRUCTURAL_RESCUE, OTHER. Priorities: LOW, MEDIUM, HIGH, CRITICAL.

Incident example:
```json
{"type":"FLOOD_RESCUE","priority":"CRITICAL","description":"Residents trapped near the access road.","location":"Kelani River Access Road, Colombo District","latitude":6.96,"longitude":79.9,"peopleNeedingAssistance":5}
```
Team example:
```json
{"name":"District Rescue Unit A","specialization":"FLOOD_RESCUE","location":"Peliyagoda","latitude":6.96,"longitude":79.88,"status":"AVAILABLE"}
```
No automated tests at user request; compile/type/lint checks only. No live rescue dispatch or external data change during implementation. Manual commit: `feat: add rescue incidents and team management`. Next: final step 6, team assignment, operation progress and completion in frontend/backend; keep Git author Sanuk.

## Final step 6 — assignment, progress and completion

Open a saved incident in Rescue Operations. Load/select an available team, enter a coordinator note and assign. Update ASSIGNED → EN_ROUTE → ON_SITE → COMPLETED, or cancel an active assignment. Optional manually confirmed team coordinates/address can accompany progress. Completed/cancelled assignments release the team to AVAILABLE. Multiple available teams may support one incident, but each team can have only one active incident. Resolve requires at least one completed assignment and no active assignments; close requires RESOLVED. History records the officer, time, request ID and operation.

POST `/incidents/{id}/assignments`: `{"requestId":"unique-id","expectedVersion":0,"teamId":"saved-team-id","teamVersion":0,"note":"Flood rescue team selected"}`. POST `/incidents/{id}/assignments/{assignmentId}/progress`: `{"requestId":"new-unique-id","expectedVersion":1,"status":"EN_ROUTE","note":"Team departed"}`; optional latitude/longitude must be supplied together, plus optional location. POST `/incidents/{id}/status`: `{"requestId":"new-unique-id","expectedVersion":4,"status":"RESOLVED","note":"Required rescue operations completed"}` then CLOSED with a new request ID/version.

Assignments and team reservation/release save together in a MongoDB transaction (Atlas/replica set required). Version checks prevent conflicting writes. Stable request IDs protect retries; different input with a used ID conflicts. No live dispatch, SMS, GPS stream, route optimization or travel-time estimates. Coordination is recorded in the coursework app, not sent to a real rescue service.

No automated tests at user request. Compile/type/lint checks only, live database transaction behavior unverified. All six implementation checkpoints now include frontend/backend. Git author Sanuk; manual commit: `feat: coordinate rescue assignments and operation completion`.
