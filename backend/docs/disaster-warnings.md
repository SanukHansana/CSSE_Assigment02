# Disaster warnings — step 3 of 6 (Anuja)

Frontend and backend together: saved warning drafts, list/detail/edit, severity, affected area, message (500 characters), safety advice (300), channel selection, future expiry and preview. Uses the supplied compose/preview screenshots for visual inspiration. Plain text keeps the code simple; rich text, optional attachments, recipient counts and automatic risk detection are not implemented. The hazard/assessment summary is officer-entered; this does not create a full hazard-assessment module or automatically approve citizen reports.

DMC_OFFICER login required. POST `/api/dmc/warnings` creates a DRAFT; GET list/detail; PUT `/{id}` replaces draft fields and requires `expectedVersion`. Lists support page/size. Author/times are server-derived, stale updates rejected, issued warnings cannot be edited. No delivery occurs yet.

Postman create example (replace validUntil with a future instant):

```json
{"hazard":"Flood — Kelani River Basin","level":"HIGH","affectedArea":"Colombo District","message":"Floodwater is rising. Residents in affected areas should remain alert.","instructions":"Move to higher ground. Avoid crossing floodwater.","channels":["SMS","PUSH_NOTIFICATION"],"validUntil":"2026-10-10T12:00:00Z"}
```

Open Disaster Warnings from Home, log in, compose, save and preview. Access and concurrency are enforced on the server. No automated tests at user request; compile/type/lint checks only, live behavior unverified.

Manual commit: `feat: add warning composition and preview workflow`
Next: step 4 broadcast simulation and delivery status on frontend/backend; keep Git author Anuja.
