# Brevo transactional email

Implemented: real warning-email broadcasts and automatic officer approval/rejection notifications. SMS, push and audible channels remain simulations. Email is the default channel in new warning drafts. No live emails were sent during implementation/testing.

## Configuration

Add the API key locally in the ignored `backend/.env` file (no quotes):

```properties
BREVO_API_KEY=YOUR_REAL_BREVO_API_KEY
BREVO_SENDER_EMAIL=pixelbluegfx@gmail.com
BREVO_SENDER_NAME=DMC Disaster Management Centre
```

The sender address is already set locally; the API key is currently empty. The key must be a Brevo API key, not an SMTP password. Enable transactional sending and verify the sender in Brevo. Never put the key in the frontend, example file, Git, or screenshots. Restart the backend after changing configuration: `./mvnw spring-boot:run`.

Official API: https://developers.brevo.com/reference/send-transac-email
Sender verification: https://help.brevo.com/hc/en-us/articles/208836149-Create-a-new-sender-From-name-and-From-email
Free-plan limits: https://help.brevo.com/hc/en-us/articles/208580669-FAQs-What-are-the-limits-of-the-Free-plan

Brevo documents a free allowance of 300 emails per day; check the current plan/account limits. One email to each recipient consumes a send. There is no real SMS integration in this application.

## Warning flow: web or mobile

1. Sign in as an approved DMC Officer; open Warnings.
2. Compose the hazard, level, affected area, message, instructions and future expiry. Select **Email (Brevo)**.
3. Save the draft and open **Review Preview**.
4. Enter 1–50 recipient addresses separated by commas. These are explicitly selected recipients, not automatic district subscriptions; account records currently do not contain district subscription data.
5. Select **Broadcast Warning Email**, review the real-send confirmation, then **Confirm Action**.
6. Inspect the channel status and provider reference. Each recipient receives a separate message version; other recipient addresses are not included in their To field.

API: `POST http://localhost:8080/api/dmc/warnings/{warningId}/email` with a DMC Officer Bearer token:

```json
{"requestId":"warning-email-001","expectedVersion":0,"recipients":["your-test-inbox@example.com"]}
```

Use the actual saved warning ID/version and your own intended test inbox. Repeat the same request ID/input to inspect the saved outcome without resending. A known FAILED rejection may be retried with a new request ID/current version using **Retry Failed Email**. Cancellation cannot recall previously accepted emails.

## Status semantics and duplicate protection

- READY: email channel selected in a simulated legacy broadcast; no real email sent.
- SENDING: send claim saved before contacting Brevo. Repeated requests return that saved claim without another provider call.
- ACCEPTED: Brevo returned a successful message reference. This is provider acceptance, not confirmed inbox delivery; webhook delivery tracking is not implemented.
- FAILED: a known provider rejection, such as invalid credentials/sender or rate limits. Correct the issue before an explicit retry.
- UNKNOWN: timeout, malformed acknowledgement, provider/server error or duplicate-key response. Check Brevo logs; automatic resend is blocked to avoid duplicate messages.

Warning claims use MongoDB version checks before external calls. If saving the final acknowledgement fails, SENDING remains stored and automatic resending remains blocked. Brevo requests also carry a deterministic UUID idempotency key; its provider-side protection lasts 30 minutes, while the application retains the claim in MongoDB. Reference: https://developers.brevo.com/docs/heterogenous-versions-batch-emails

## Officer application notifications

An Admin confirms approval/rejection in the applicant card. After the conditional database decision succeeds, the backend emails that applicant's saved account address with their decision and note. The response includes `emailNotificationStatus`, shown in the Admin result message. A notification failure does not undo approval/rejection. Concurrent/stale reviews do not send another decision email. There is no automatic notification retry/outbox for these Admin emails; inspect Brevo logs if the outcome is UNKNOWN.

## Verification

Run backend tests/coverage with `./mvnw clean verify`; frontend with `npm run test:coverage` and `npm run check`. Provider HTTP is mocked in tests, including successful/failed/uncertain sends, private recipients, idempotency, saved claims, validation, retries and account notifications. Live sender credentials, Brevo activation, account quotas and inbox delivery still require an explicit manual send to your intended inbox.
