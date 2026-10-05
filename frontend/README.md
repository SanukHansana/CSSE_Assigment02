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
