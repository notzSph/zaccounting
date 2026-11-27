# zAccounting

![Project status: in development](https://img.shields.io/badge/status-in%20development-orange)
![Java 21](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)
![Spring Boot 4.2.0](https://img.shields.io/badge/Spring%20Boot-4.2.0-6DB33F?logo=springboot&logoColor=white)
![PostgreSQL 18.7](https://img.shields.io/badge/PostgreSQL-18.7-4169E1?logo=postgresql&logoColor=white)
![License: MIT](https://img.shields.io/badge/License-MIT-lightgrey.svg)

Self-hosted accounting software in active development. The project is being rebuilt as a Next.js application with a Java/Spring Boot API and PostgreSQL.

> **Early development:** The target application is incomplete and is not ready for production accounting or real financial data.

## Project status

- The legacy Next.js application is maintained outside this repository and is intentionally excluded from commits.
- `apps/api/` contains the new Spring Boot API. Its current vertical slice connects to PostgreSQL and exposes `GET /api/v1/health`.
- `apps/web/` has not been initialized yet.
- `infra/docker/compose.yaml` currently provides the PostgreSQL development service. Web, API and Caddy containers are not configured yet.

The intended product includes multi-business bookkeeping, a double-entry ledger, approvals, financial statements, imports and an API for scoped agent access. These are roadmap items, not claims about current functionality.

## Target architecture

- **Web:** TypeScript, Next.js and React in `apps/web/`
- **API:** Java, Spring Boot and Spring MVC in `apps/api/`, organized as one modular monolith
- **Database:** PostgreSQL with Flyway-owned schema migrations
- **Deployment:** Docker Compose and Caddy on a VPS

Next.js will handle presentation. Spring Boot will own authentication, authorization, accounting rules, imports, reporting and database access. The browser and external agents will use the same versioned REST API.

See [the architecture overview](docs/architecture.md) and [migration roadmap](docs/migration-roadmap.md) for boundaries and planned phases.

## Local development

### Requirements

- Java 21 JDK
- Docker Engine and Docker Compose
- Bash-compatible shell for the environment-loading commands below

### Start PostgreSQL and the API

From the repository root, create a local environment file and replace its sample password:

```bash
cp -n infra/secrets/.env.example infra/secrets/.env
vim infra/secrets/.env
```

Start the database:

```bash
sudo docker compose --env-file infra/secrets/.env -f infra/docker/compose.yaml up -d db
```

Then start the API from `apps/api`:

```bash
cd apps/api
set -a
source ../../infra/secrets/.env
set +a
./mvnw spring-boot:run
```

In another terminal, check the liveness endpoint:

```bash
curl --fail http://localhost:8080/api/v1/health
```

Expected response:

```json
{"status":"UP"}
```

The endpoint reports that the API process is responding. It does not report database health. More detail is in [local development](docs/local-development.md).

### Run API tests

With PostgreSQL running, from `apps/api`:

```bash
set -a
source ../../infra/secrets/.env
set +a
./mvnw test
```

## Repository layout

```text
apps/
  api/                 Spring Boot API
  web/                 Planned Next.js application
docs/                  Architecture, roadmap and development guides
infra/
  docker/              Docker Compose and container configuration
  secrets/             Local environment files, ignored by Git
```

The legacy source application is kept separately and is not included in this repository.

## Contributing and security

Read [CONTRIBUTING.md](CONTRIBUTING.md) before proposing changes. See [SECURITY.md](SECURITY.md) for private vulnerability reporting. This project is not yet suitable for production use.

## License

This project is licensed under the [MIT License](LICENSE).
