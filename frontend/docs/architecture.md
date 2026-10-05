# Architecture and coding conventions

## Dependency flow

```text
App (composition root)
  └─ DmcScreen → useDmcStatus → DmcService contract
                                  └─ createDmcService → HttpClient contract
                                                         └─ fetch adapter
```

`src/application/services.ts` constructs the real implementations and injects them into the screen. Features do not import the composition root. Shared components do not import feature code. The HTTP layer does not know about DMC response fields; the feature's schema validates them before returning typed data.

## SOLID in this starter

| Principle             | Concrete application                                                                                                                                                                                      |
| --------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Single responsibility | Screen renders, hook manages request state, service selects the endpoint and validates data, HTTP adapter performs transport.                                                                             |
| Open/closed           | Add another implementation of `DmcService` or `HttpClient` without rewriting its consumers.                                                                                                               |
| Liskov substitution   | Alternate implementations must return validated results and preserve cancellation and rejection behavior promised by the contracts. Type compatibility alone does not guarantee behavioral compatibility. |
| Interface segregation | `HttpClient` exposes only the GET operation currently needed; `DmcService` exposes only the status use case. Expand contracts only for real requirements.                                                 |
| Dependency inversion  | Feature logic depends on `HttpClient`, and presentation depends on `DmcService`; the composition root supplies concrete adapters.                                                                         |

The scaffold demonstrates these principles; maintaining them requires applying the dependency rules as features grow. Avoid unnecessary classes, singleton registries, generic repositories, or interfaces for every helper.

## Patterns used

- **Dependency injection:** the service receives an HTTP client; the screen receives a service.
- **Adapter:** `createFetchHttpClient` translates the transport API to an application contract.
- **Factory:** creation functions assemble implementations with explicit dependencies.
- **Composition:** reusable controls and layout are combined into screens.
- **Boundary validation:** `parseDmcStatus` rejects malformed responses at runtime.
- **Discriminated state:** idle/loading/success/error variants prevent incompatible UI state combinations.

## Add a feature

1. Create `src/features/<feature>/` with only the folders it needs.
2. Define its domain types and validate incoming payloads in `schemas/`. Use a schema library when validation becomes complex.
3. Define a small service contract and implement its use cases with injected dependencies.
4. Construct the implementation in `src/application/services.ts`.
5. Put asynchronous state in feature hooks and render it through screens and components.
6. Handle loading, errors, cancellation, and malformed data. Keep credentials out of the client.
7. Add meaningful tests for domain rules, schema boundaries, and request behavior when extending them. Test services with an in-memory `HttpClient` implementation instead of a live backend.
8. Run `npm run check` and verify the changed screen on the target platforms.

Keep functions focused; remove duplication only when it represents the same behavior. Avoid `any`, giant components, deeply nested conditionals, silent error handling, and network calls inside presentational components. Prefer direct imports over broad barrel files, and keep global state limited to genuinely shared state.

## Future navigation

The one-screen starter does not install a navigation framework. When navigation is needed, use Expo Router and reserve `src/app/` for route files. Keep dependency wiring and providers in `src/application/`, and keep feature implementation outside the route directory.
