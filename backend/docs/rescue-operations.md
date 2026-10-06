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
