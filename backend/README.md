# DMC backend

A Spring Boot 4.1.1 REST API using Maven, Spring MVC, validation, and Spring Data MongoDB. The project targets Java 21; your installed Java 25 can run it.

## Run for the first time

1. Open a terminal in the project root (`DMC`) and enter the backend:

   ```sh
   cd backend
   java -version
   ./mvnw spring-boot:run
   ```

   You need a JDK version 21 or newer supported by Spring Boot. Maven Wrapper (`mvnw`) downloads Maven and dependencies automatically, so you do not need a separate Maven installation. The first run needs internet access and may take a few minutes. On Windows use `mvnw.cmd spring-boot:run`.

2. Wait for `Started DmcBackendApplication`, then open <http://localhost:8080/api/dmc> in your browser, or run this in another terminal:

   ```sh
   curl http://localhost:8080/api/dmc
   ```

   Expected JSON (field order may differ):

   ```json
   { "status": "UP", "application": "dmc-backend" }
   ```

3. Stop the server with **Ctrl+C**. Restart it after changing Java code.

The root URL `/` has no controller, so it returns 404. Use `/api/dmc` instead. If port 8080 is occupied, run `PORT=8081 ./mvnw spring-boot:run` and open port 8081.

## Add your MongoDB connection later

The database connection point is `src/main/resources/application.properties`:

```properties
spring.mongodb.uri=${MONGODB_URI:mongodb://localhost:27017/dmc}
```

Until you supply a URL, the driver tries a local MongoDB instance. The application and `/api/dmc` can run without a database, but you may see MongoDB connection-refused messages. The health endpoint only checks the web application; it does **not** prove the database is connected. Database operations require a reachable MongoDB server.

For local development, run these commands from `backend`:

```sh
cp application-local.properties.example application-local.properties
```

Open `application-local.properties` and replace `spring.mongodb.uri` with your MongoDB URL. For example:

```properties
spring.mongodb.uri=mongodb+srv://USERNAME:PASSWORD@YOUR_CLUSTER.mongodb.net/dmc?retryWrites=true&w=majority
```

Use your actual cluster, database user, and password. Include a database name such as `/dmc`. For Atlas, allow your development machine's IP in the cluster's network access settings and use a database user's credentials. Percent-encode special characters in the username/password in the URI. The local properties file is ignored by Git; keep credentials out of committed files.

Alternatively, set the `MONGODB_URI` environment variable in your terminal or hosting service before starting the application. A value explicitly set in `application-local.properties` takes precedence over the placeholder above, so use one approach at a time. The custom authentication configuration now imports `.env` as a Java properties file; use unquoted `KEY=value` lines (see the authentication guide). Restart after changing configuration. No custom MongoClient class is needed: Spring Boot configures it using the MongoDB starter and URI.

## Project structure

```text
backend/
├── pom.xml                         # Dependencies and build settings
├── mvnw / mvnw.cmd                 # Maven Wrapper for macOS/Linux / Windows
├── .mvn/wrapper/                   # Wrapper configuration
├── application-local.properties.example
└── src/
    ├── main/
    │   ├── java/com/dmc/backend/
    │   │   ├── DmcBackendApplication.java  # Application entry point
    │   │   ├── config/             # Spring configuration classes
    │   │   ├── controller/         # HTTP endpoints
    │   │   ├── middleware/         # Filters and interceptors
    │   │   ├── models/             # MongoDB document classes
    │   │   ├── repository/         # Database access interfaces
    │   │   ├── routes/             # Shared URL constants
    │   │   ├── service/            # Business logic
    │   │   └── utils/              # Reusable helpers
    │   └── resources/
    │       └── application.properties
    └── test/java/com/dmc/backend/   # Automated tests
```

Java folders beneath `src/main/java` are called packages. Keep new classes under `com.dmc.backend` so Spring can discover them automatically. `package-info.java` documents packages that are ready for future code.

Spring controllers define routes through `@GetMapping`, `@PostMapping`, and similar annotations. The `routes` package holds shared paths; no separate route registration file is required. Spring middleware usually means servlet filters or MVC interceptors. A typical feature flows through **controller → service → repository → MongoDB**, with a class annotated `@Document` in `models` and an interface extending `MongoRepository<Model, String>` in `repository`. Ground hazard report models and reporter draft/submission endpoints are implemented; see the Stage 2 guide below.

## Test and build

Run from `backend`:

```sh
./mvnw test
./mvnw clean package
java -jar target/backend-0.0.1-SNAPSHOT.jar
```

The test starts the web application on a random port and checks `/api/dmc`; it does not read or write MongoDB. `package` also runs tests and creates the executable JAR. Stop any server already on port 8080 before running the JAR.

Official references: [Spring Boot requirements](https://docs.spring.io/spring-boot/system-requirements.html), [MongoDB configuration](https://docs.spring.io/spring-boot/reference/data/nosql.html).

## Ground hazard reporting — Stage 1

The backend now includes the ground hazard report domain model and MongoDB persistence foundation. Reporting endpoints are not exposed yet. See the [domain and proposed API contract](docs/ground-hazard-report-api.md) and [explicit migration instructions](migrations/README.md). The existing `/api/dmc` response is unchanged. Authentication integration and private evidence storage remain prerequisites for the later endpoint stages.

## Custom JWT authentication

The separately authorized custom authentication prerequisite adds registration, login, and authenticated account retrieval. See [setup and API documentation](docs/custom-authentication.md). Start from `backend` so its ignored `.env` is loaded. Ground-reporting Stage 2 now reuses this authentication module.

## Ground hazard reporting — Stage 2

Reporter-owned draft creation/editing, photo upload/view/download, report listing/details, and validated submission are implemented. Citizen and Community Volunteer roles are supported. Read the [Stage 2 API and storage guide](docs/ground-hazard-report-stage2.md) for endpoints, version headers, private evidence storage, and error responses. Frontend work, officer review endpoints, and offline synchronization remain later stages.

## Ground hazard reporting — Stage 3

Duty Officers can search the review queue, inspect reports/evidence, claim a review, save credibility checks/comments, and verify or reject reports. Decisions and history use the existing versioned single-document persistence. See the [Stage 3 officer API](docs/ground-hazard-report-stage3.md). Synchronization, verified-evidence integration, and frontend implementation remain later work.

## Simpler report model organization

Small identity, location, photo, and history records are nested within `HazardReport`, alongside its validation helpers. `ReportReview`, `ReportVerification`, and workflow enums remain separate. Existing MongoDB field names and HTTP contracts are unchanged; no data migration is needed.

## Ground hazard reporting — Stage 4

Completed locally queued reports can be accepted through duplicate-safe synchronization. DMC Officers can access verified supporting reports and photos. See the [Stage 4 API and retry contract](docs/ground-hazard-report-stage4.md). Device offline storage and automatic retry scheduling remain frontend work.
