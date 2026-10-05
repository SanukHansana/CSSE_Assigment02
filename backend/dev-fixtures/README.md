# Fictional development fixtures

Fixture generation is test-only. It writes local files under `target/development-fixtures` and never contacts or seeds a database. Four fake accounts cover Citizen, Community Volunteer, Duty Officer and DMC Officer. Five reports cover DRAFT, SUBMITTED, UNDER_REVIEW, VERIFIED and REJECTED; the submitted example is from a volunteer. Four synthetic PNGs explicitly say they are fictional. Accounts use bcrypt hashes, and reports follow domain validation/BSON mapping.

Generate from backend:

```sh
./mvnw -Dtest=DevelopmentFixturesTest test
```

Files: `target/development-fixtures/fixtures.json` (MongoDB Extended JSON) and `target/development-fixtures/evidence/`. `clean` removes target, so copy evidence to persistent private demo storage before starting a demo. No real citizen data is included.

| Email | Role |
| --- | --- |
| citizen@dmc.example.test | CITIZEN |
| volunteer@dmc.example.test | COMMUNITY_VOLUNTEER |
| officer@dmc.example.test | DUTY_OFFICER |
| dmc@dmc.example.test | DMC_OFFICER |

All four use the development-only password `DemoReports2026!`. These are intentionally known demo credentials. Import them only into the dedicated `dmc_demo` database. Do not use these accounts/passwords in another database or expose a seeded demo publicly.

## Optional explicit import

No import was executed by the agent. To demonstrate later:

1. Privately configure your MongoDB URI to select a dedicated **dmc_demo** database.
2. Apply migrations 001 and 002 to that database using the existing migration instructions.
3. Generate the fixture files.
4. Run from backend with the securely supplied demo URI:

```sh
mongosh "$MONGODB_URI" --file dev-fixtures/import.js
```

The script refuses every database name except dmc_demo. It inserts only IDs that do not exist and never overwrites/deletes existing users or reports. It reads from `target/development-fixtures` by default; set DMC_FIXTURE_DIRECTORY if generated files have been copied elsewhere. Duplicate email/index errors are left visible. Like existing migrations, importing is not a multi-command transaction; a partial run can be repeated. Stable demo user/report IDs avoid duplicate records; existing fixture records retain their original photo references.

5. Copy **all** generated evidence files into a separate persistent private demo directory, preserving filenames. Example, from backend:

```sh
mkdir -p .data/demo-hazard-evidence
cp -R target/development-fixtures/evidence/. .data/demo-hazard-evidence/
```

6. Start the application with its database configured to dmc_demo and DMC_EVIDENCE_DIRECTORY pointing to that copied demo directory. Existing local properties may override the database configuration; confirm the intended database through your private configuration. Do not mix demo evidence/database settings with your regular data.
7. Use the existing login endpoint with a demo email/password and send the returned bearer token to the appropriate reporting routes.

Tests/mapping validation prove fixture consistency, not that the import script was executed against a live database. Rerunning the generator can produce fresh evidence files/hashes; already imported fixed report IDs are skipped, so preserve the existing demo evidence directory.
