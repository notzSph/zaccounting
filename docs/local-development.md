# Local development database

The Compose file is `infra/docker/compose.yaml`. PostgreSQL is pinned to the supported 18.7 image and maps host port `25434` to container port `5432`, bound to `127.0.0.1`. Maven can reach the database on the host while the database remains unavailable to remote hosts. The same Compose file is used on zHub. The environment template also reserves host ports `3202` for web and `8202` for API when those services are added.

## Start PostgreSQL on the development machine

From the repository root:

1. Copy the environment template to `infra/secrets/.env` and replace the sample password. Files in `infra/secrets/` are ignored by Git except `.env.example`.

   ```bash
   cp -n infra/secrets/.env.example infra/secrets/.env
   vim infra/secrets/.env
   ```

2. Start the database. The named volume keeps database files when the container is recreated.

   ```bash
   sudo docker compose --env-file infra/secrets/.env -f infra/docker/compose.yaml up -d db
   ```

3. Confirm it is healthy.

   ```bash
   sudo docker compose --env-file infra/secrets/.env -f infra/docker/compose.yaml ps
   ```

## Run the Spring Boot test from the host

From `apps/api`, export the local connection settings from `infra/secrets/.env`, then run Maven:

```bash
set -a
source ../../infra/secrets/.env
set +a
./mvnw test
```

Spring reads the JDBC URL, username and password from environment variables. Flyway initializes the schema before JPA starts.

## Check the API health endpoint

With PostgreSQL running and the environment variables loaded, start the API from `apps/api`:

```bash
./mvnw spring-boot:run
```

In another terminal, request the public liveness endpoint:

```bash
curl --fail http://localhost:8080/api/v1/health
```

It returns `{"status":"UP"}` when the API process is responding. It is a liveness check and does not report database health.

## Deployment notes

The same Compose file is used on zHub. PostgreSQL binds only to `127.0.0.1:25434` on the host. The API container will connect over the Compose network using the service hostname `db` and port `5432`; Caddy will route external traffic to the web and API services on ports `3202` and `8202` respectively. Do not expose PostgreSQL publicly.
