# Running MarketOps Russia on a workstation

This runbook takes a clone to a running backend, a running console, and a
verified local setup. It assumes nothing is installed beyond the prerequisites
listed below, and it installs nothing on your behalf.

## Prerequisites

| Tool | Version | Why this one |
| --- | --- | --- |
| Java | 21 | The build refuses any other major version, so a mismatch fails at the start rather than at a confusing later step |
| Node | 24.19.0 | Pinned in `.node-version`; `engine-strict` turns a mismatch into a refusal instead of a warning |
| A container runtime | Any that provides `docker compose` | The database runs in a container so nothing is installed on the host |
| Python | 3.11 or newer | The governance and readiness validators use the standard library only |
| GNU Make | Any | Entry points are collected in one file rather than in a wiki page |

Run `make doctor` before anything else. It reports what is missing and changes
nothing.

## First run

```
make bootstrap     # generate .env.local files and report prerequisites
make up            # start PostgreSQL and wait until it is usable
make backend-run   # start the backend against the local database
```

In a second terminal:

```
make frontend-install
make frontend-dev
```

The console is then at <http://127.0.0.1:5173>. Both processes bind to loopback
for workstation use. The console API requires a validated bearer token and
refuses access while OIDC is unconfigured. Only the explicitly public health,
build and metadata paths are available before login. The maintenance surface
has a separate local write gate and must never be published to the network.
See [identity and mounted secrets](identity-and-mounted-secrets.md) for the
authentication boundary and local verification limits.

## What `make bootstrap` writes

Two files, both ignored by Git, both created with owner-only permissions:

| File | Contents |
| --- | --- |
| `.env.local` | Three generated database passwords and the published database port |
| `frontend/marketops-console/.env.local` | The backend origin and the environment name |

Generated passwords are never printed, on any code path. If you need to know
whether the backend read the file, run `make verify-local-config`: it starts the
backend, waits for readiness — which requires the generated application password
— and reports the result without disclosing any value.

Database role names are not generated. `marketops_migration` and
`marketops_app` are identical in every environment and are not secrets, so they
are checked in. A workstation and continuous integration cannot disagree about
who owns a schema.

## Everyday commands

| Command | What it does |
| --- | --- |
| `make doctor` | Report unmet prerequisites; changes nothing |
| `make up` / `make down` | Start or stop the database, keeping its data |
| `make reset` | Delete the database volume; asks for confirmation first |
| `make backend-test` | Clean full backend verification, including unit, architecture, real database and Linux filesystem tests; requires Docker |
| `make backend-arch` | Run only the boundary rules and their fixtures |
| `make backend-verify`, `make backend-check`, `make backend-integration` | The same clean full verification and unchanged JaCoCo 80% line / 70% branch gate |
| `make frontend-check` | Lint, formatting, types, tests with coverage, and build |
| `make governance` | The governance and production readiness rules |
| `make verify` | All of the above |

## Stopping and starting again

`make down` stops the container and keeps the volume, so the next `make up`
finds the schemas already migrated. `make reset` deletes the volume; the next
`make up` reinitialises the cluster and reruns the role script.

The role script runs exactly once, when the data directory is empty. Changing
`infra/compose/postgres-init/sql/01-roles.sql` therefore has no effect on an
existing volume — `make reset` is what applies it.
