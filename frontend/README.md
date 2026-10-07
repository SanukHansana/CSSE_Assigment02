# DMC frontend

React Native + Expo + TypeScript, initialized with the official `blank-typescript` template.

## Run

```sh
cd frontend
npm ci
npm start
```

Scan the QR code using Expo Go with a compatible SDK, or press `i` for the iOS simulator / `a` for the Android emulator. Simulators require the corresponding native tooling. Use `npm run web` for the browser version.

Start the Spring Boot backend separately from `backend` with `./mvnw spring-boot:run`. The starter screen's **Check connection** button requests `GET /api/dmc` and displays the application's status. This endpoint reports API availability, not database connectivity.

The default API address is `http://localhost:8080` on iOS/web and `http://10.0.2.2:8080` on Android. On a physical device, copy `.env.example` to `.env` and set `EXPO_PUBLIC_API_BASE_URL` to your computer's LAN address, for example `http://192.168.1.10:8080`. Your phone must be able to reach that address. Restart Expo after configuration changes. Browser requests across origins need backend CORS configuration or a same-origin proxy; no CORS policy is added by this scaffold.

Every `EXPO_PUBLIC_*` variable is included in the client bundle. Keep database credentials and other secrets in the backend only. `.env` is ignored; `.env.example` is safe to commit.

## Structure

```text
frontend/
├── App.tsx                         # Thin bridge to src/application/App.tsx
├── index.ts                        # Expo entry registration
├── app.json                        # Expo app configuration
├── assets/                         # App icons and splash assets
├── .env.example                    # Public configuration template
└── src/
    ├── application/
    │   ├── App.tsx                 # Root providers and initial screen
    │   └── services.ts             # Composition root / dependency wiring
    ├── components/
    │   ├── layout/Screen.tsx       # Reusable safe-area layout
    │   └── ui/Button.tsx           # Reusable presentational controls
    ├── config/env.ts               # Runtime configuration
    ├── constants/apiRoutes.ts      # API paths
    ├── features/
    │   └── dmc/
    │       ├── hooks/              # Feature state and request lifecycle
    │       ├── schemas/            # Runtime validation of API/form data
    │       ├── screens/            # Screen composition and presentation
    │       ├── services/           # Feature contracts and use cases
    │       └── types/              # Domain and response types
    ├── services/http/              # Shared HTTP contract and fetch adapter
    ├── theme/theme.ts              # Shared colors, spacing, and radii
    └── utils/getErrorMessage.ts    # Small, pure shared helpers
```

Keep feature-specific components, hooks, schemas, utilities, and services inside their feature. Move code into shared folders only when several features need it. Add navigation, storage, and state libraries when a real feature requires them; do not create unused infrastructure.

## Quality checks

```sh
npm run check       # TypeScript, ESLint, and formatting
npm run format     # Apply consistent formatting
```

Use PascalCase for component/type names and component filenames, camelCase for functions/hooks, and `use` prefixes for hooks. Use type-only imports for contracts, strict TypeScript, unknown for external data, and StyleSheet plus shared theme tokens for presentation. Document contracts, boundaries, and non-obvious decisions rather than every line.

See [architecture.md](docs/architecture.md) for SOLID examples, dependency rules, and the feature extension workflow.

Official reference: [Expo create-expo-app templates](https://docs.expo.dev/more/create-expo/).

## Dependency audit

The initial npm audit reports 22 advisories (7 moderate, 15 high), inherited through the Expo/React Native dependency tree. Audit suggests incompatible Expo/React Native downgrades for several entries; do not run `npm audit fix --force` blindly. Recheck advisories and compatible upstream fixes before production release.

## Ground reporting frontend — checkpoint 1 of 2

The home page and reporting workspace use the supplied screenshots as visual inspiration. Expo Router starts at `src/app/index.tsx`; open **Ground Reports** to register or sign in. Only Citizen/Community Volunteer accounts submit reports. Duty Officer accounts now open the review workspace automatically after login.

This checkpoint adds in-memory login (reload requires signing in again), registration, new/existing drafts, JPEG/PNG photo selection and private previews, GPS capture/manual coordinates with external OpenStreetMap link, save/reload, review/confirm submission, and paginated My Reports/status/history. Mutations use the latest acknowledged version. On a conflict reload the saved report; this discards unsaved edits. If a request fails with an unknown server outcome, inspect My Reports before creating another report. No durable offline queue or automatic retry is claimed. No embedded live map is implemented yet.

Run the backend separately, then `npm run web` (default Expo web origin should match the backend `WEB_ALLOWED_ORIGINS`, normally `http://localhost:8081`). If Expo selects another port, configure the matching backend origin and restart it. For physical devices use `EXPO_PUBLIC_API_BASE_URL` with the computer LAN IP; localhost refers to the device itself. No secrets belong in frontend environment variables.

Manual smoke check with your development database: register a unique citizen, sign in, save an incomplete draft, reload via My Reports, add valid coordinates/description/photo, save again, review/confirm submit, and verify status/history. Check denied GPS permissions retain coordinates, invalid uploads preserve the saved draft, sign-out clears account state, and stale versions require reload. Real MongoDB/browser/native-device end-to-end tests remain manual.

Suggested first commit: `feat: add authenticated ground hazard report submission`. Include the still-uncommitted home/navigation foundation in this first frontend commit. Next (after manually committing): officer queue, checklist/comments, verify/reject, and verified details matching the other screenshots. No automatic commits or Git author changes.

## Ground reporting frontend — checkpoint 2 of 2

Duty Officer login opens the screenshot-inspired navy sidebar and review workspace. Search/paginate by report status, read details/private photo/location/history, explicitly claim a submitted report, save the four tri-state credibility checks and comments, and confirm verification/rejection. All four checks must pass for verification and rejection requires a reason. Each update uses the acknowledged backend version; conflicts instruct the officer to reload. Terminal reports show the saved decision, officer, comments, checks, timestamp, and verification reference. No fabricated risk score, assessment link, or automated warning is displayed. Authorization and assignment ownership are enforced by the backend.

For a manual end-to-end check, submit as a citizen, sign out, sign in with a development DUTY_OFFICER account, refresh the submitted queue, inspect details, start review, save checks/comments, confirm verification, and check the VERIFIED filter. Repeat on another report for rejection and its required reason. Test stale versions with two sessions and another officer assigned to the same report. Public registration creates only citizens; see ../backend/dev-fixtures/README.md for optional isolated demo account setup. Do not assume fixture accounts exist until imported.

Checkpoint 2 verification: TypeScript, lint, and web export pass. Live database/browser/native-device end-to-end behavior remains unverified. Manual commit message: `feat: add officer review and verified report screens`. Next step: run both frontend/backend and perform the end-to-end smoke check before starting another feature.

## Registration and admin approval update

Registration now offers Citizen, Community Volunteer, Duty Officer, and DMC Officer. Citizens/volunteers activate immediately; officers see a pending-status screen until an Admin approves them. The 12-character minimum is removed; passwords must be nonblank and at most 72 UTF-8 bytes. Home → Admin verification opens review for MongoDB-configured Admin accounts only. See ../backend/docs/account-registration-and-approval.md for bootstrap/migration and API instructions. Earlier Citizen-only registration statements are superseded by this update. No live accounts or secrets were changed.

## Navigation, admin review, and map UI update

The full-width root navigation shares the in-memory session across routes. Guests see Sign in/Create account; signed-in users see their name, role-specific workspace links, and Log out. Sessions still reset on a full browser reload. DMC Officers get a response workspace dashboard; Duty Officers get report review. Admin confirmation, decision notes, and errors now appear inside the selected application card. Shared navy/teal controls distinguish primary, secondary, approval and rejection actions.

Hazard-report, rescue-incident/team and rescue-progress forms provide a click/tap location picker using Leaflet/OpenStreetMap, with drag-to-adjust markers and coordinate fields retained as a fallback. The web picker renders Leaflet directly so tile requests include the normal page referrer; mobile uses an app-identified Expo-compatible WebView with validated selection messages. Maps fetch public Leaflet CDN assets and OpenStreetMap tiles and need an internet connection; no Google Maps key or billing is configured. These maps show selected/stored locations, not live team tracking.

The stale second-commit message and empty-string JSX location condition were corrected. Frontend type/lint/web-build checks are used; native devices and real database approval/dispatch workflows still need manual verification. No real user accounts, backend secrets, or Git author settings are modified by this UI update. Suggested manual commit: `feat: improve role navigation admin review and map selection`.

Browser UI checks with fictional, isolated accounts confirmed the full-width navigation, inline Admin confirmation, DMC Officer dashboard, and login persistence across workspaces. No live MongoDB accounts were used.

The browser preview also confirmed OpenStreetMap tiles render and clicking the map fills coordinates without the previous text-node error. Native map behavior and real database writes remain unverified; the preview used only fictional local responses.
