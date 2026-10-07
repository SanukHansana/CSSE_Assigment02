# Community support using personal resources

Citizens and Community Volunteers activate through public registration. DMC/Duty Officer applications still require Admin approval. No additional account role is needed.

## Application flow

1. Sign in as a Citizen. Select **My Needs** in the top navigation (or Home → Community support).
2. Enter the item, category, whole quantity, unit, description and area, then **Post Need**. Use **Refresh Needs** to browse your requests.
3. Sign out and sign in as a Community Volunteer. Select **Community Support** → **Refresh Needs** → **View Need**.
4. Enter the quantity and a note describing your own supplies and delivery arrangements. Select **Offer Personal Resources**.
5. The Citizen opens the same need through My Needs and selects **Accept Offer** or **Decline Offer**.
6. The Volunteer opens **My Offers**, refreshes, views the accepted offer and selects **Mark Delivered**.
7. The Citizen opens the need and selects **Confirm Receipt**. The request becomes FULFILLED only when confirmed receipts cover the full requested quantity. Partial receipts leave it OPEN.

The Citizen can cancel an open need after accepted/delivered offers are resolved. Volunteers can withdraw offered or accepted supplies before delivery. These supplies do not reduce official DMC resource inventory. Delivery is manually confirmed; the application does not dispatch shipments or payments.

## HTTP API

Base URL: `http://localhost:8080/api/dmc/community/needs`. Set Postman's Bearer Token to the appropriate login token. JSON requests use `Content-Type: application/json`.

Create as Citizen: `POST /api/dmc/community/needs`

```json
{"item":"Drinking water","category":"WATER","quantity":10,"unit":"bottles","description":"Our household needs drinking water after flooding.","area":"Colombo District"}
```

List: Citizen `GET ?view=mine&page=0&size=10`; Volunteer `GET ?view=available&page=0&size=10` or `GET ?view=offers&page=0&size=10`.

Detail: `GET /{needId}`. Copy the returned version for each update.

Offer as Volunteer: `POST /{needId}/offers`

```json
{"requestId":"volunteer-offer-001","expectedVersion":0,"quantity":10,"note":"I can provide ten bottles from my own supplies and arrange delivery."}
```

Use the returned offer ID for `POST /{needId}/offers/{offerId}/action`:

```json
{"requestId":"citizen-accept-001","expectedVersion":1,"action":"ACCEPT"}
```

Next, the offering Volunteer sends DELIVER; then the requesting Citizen sends RECEIVE, each with the current expectedVersion and a new requestId. DECLINE is Citizen-only; WITHDRAW is offering-Volunteer-only.

Cancel as the requesting Citizen: `POST /{needId}/cancel`

```json
{"requestId":"citizen-cancel-001","expectedVersion":3}
```

Use a new request ID for every distinct operation. If a connection fails after sending, retry the same operation with the same ID and input, or reload the record to inspect its saved result. Reusing an ID with different input is rejected. Stale versions and invalid transitions return 409; restricted roles/owners return 403; inaccessible needs return 404. Requests are saved in `community_needs` with MongoDB optimistic versioning.

## Data and scope

The board exposes names, areas and request descriptions needed for support, but does not expose account IDs, emails or password hashes. The Citizen sees all offers for their own need. Each Volunteer sees only their own offer details. Submission fields are required and bounded; quantities must be whole numbers from 1 to 1,000,000. Board pages are bounded to 1–100 records.

Unsaved forms remain in memory only. Refreshing the browser resets login; sign in again. Recheck the existing needs list after an uncertain create response before posting a duplicate need.
