# When something does not work

Each entry names what you see, what it means, and what to do. They are ordered
by how often they have actually come up.

## `make up` stops with "set it in .env.local via make env-init"

The compose file declares every password mandatory, so a missing value stops the
stack instead of starting a database whose credentials nobody knows.

Run `make env-init`. If the file exists but a variable is empty, delete the file
and generate it again; the generator refuses to overwrite silently, so pass
`--force` from an interactive terminal.

## The backend starts, then fails with an authentication error

The database was initialised with different passwords than the ones the backend
is reading. The role script runs exactly once, when the data directory is empty,
so regenerating `.env.local` after the first `make up` produces exactly this.

`make reset` deletes the volume and reinitialises the cluster with the current
passwords. It destroys local data and asks for confirmation.

## The migration fails with a duplicate schema

The database already contains one of the eight foundation schemas, created by
something other than the migration. That schema belongs to whatever created it,
and the migrating role cannot write to it.

This is a refusal, not a defect. `make reset` clears the volume. Tolerating the
existing schema would defer the failure to a later change and disguise it as an
unexplained permission problem.

## The migration failed and I am looking for a failed row to repair

There is not one, and there is nothing to repair. PostgreSQL applies each
migration inside a transaction, so a failure rolls back the schema creation and
the history insert together. The history table is present, and it holds no
record of the attempt.

A procedure written for a database without transactional schema changes expects
a row marked unsuccessful and a repair command. Running that command here would
be a change with nothing to change.

## `make backend-run` cannot find the local configuration

The backend is started with an absolute import path pointing at the repository
root. If the path contains a space or a single quote, `make` refuses to continue
rather than passing a value that would be split.

Move the clone to a path without those characters. `make doctor` reports this
before anything else has run.

## The console shows "unreachable" while the backend is running

Three things produce this, in order of likelihood.

The console is pointed somewhere else: check `VITE_MARKETOPS_API_BASE_URL` in
`frontend/marketops-console/.env.local`. The footer of the console always names
the origin it is using.

The backend is bound to loopback and the browser is not on the same host. That
is deliberate; there is no authentication in this foundation.

The request took longer than the console waits. The console abandons a request
rather than holding the screen indefinitely, and reports the class of failure
without repeating the network message, which can name an internal host.

## The console shows "degraded"

The backend answered and reported that its database did not. The backend is
running; the database is not reachable from it. Check that the container is up
(`docker compose ps` from `infra/compose`) and that the backend was started with
the passwords the cluster was initialised with.

## The console shows "malformed"

The backend answered with a payload the console cannot read. The two are from
different releases, or a field was removed without the console being updated.
The console reports this rather than rendering the fields that did arrive,
because a partially populated screen looks like a working one.

## An architecture test fails and I do not recognise the rule

Each rule states, in its own text, the consequence it exists to prevent. The
failure message names the class and the dependency that broke it.

If the rule looks wrong, change the rule and its fixture together in the same
change, and say in the message what the project now believes. A rule weakened
without its fixture keeps passing and stops checking.

## A test fails only in continuous integration

Look at the time zone and the locale first. The workflows set both explicitly
because a runner's defaults are not guaranteed between images, and a workstation
usually has neither set to the same value.

Then look at whether the test reads the ambient clock. An architecture rule
forbids that in production code; a test can still do it, and the failure then
depends on the moment the suite ran.

## `npm ci` fails with an engine error

The Node version does not match `.node-version`. `engine-strict` in `.npmrc`
turns that into a refusal rather than a warning, so a build never silently uses
a runtime nobody tested.
