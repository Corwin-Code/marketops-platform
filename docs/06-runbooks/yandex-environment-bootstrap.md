# Building a MarketOps environment on Yandex Cloud

```yaml
document_type: environment_bootstrap_procedure
executed: NEVER
apply_authority: OWNER_ONLY
```

## Status

**No environment has been built from this.** The configuration in
`infra/yandex` has never been applied, no state file exists, and no credential
for a Yandex Cloud account is present in this repository or in the environment
this work was produced in. Applying it is an Owner-authorized act.

## Order, and why it is this order

1. **Secrets before infrastructure.** Every credential the environment needs
   exists in Lockbox before anything reads one. Creating the database and then
   deciding where its password lives is how a password ends up in a variables
   file.
2. **Identities before the things they may touch.** The evidence bucket's policy
   names the identities permitted to reach it, so those identities must exist
   first. This is why `workload_identity` is composed before `object_storage`.
3. **Infrastructure before the application.** Obvious, and stated because the
   temptation under time pressure is to run the application against a
   half-built environment "just to see".
4. **Migrations before the application starts.** The application refuses to
   start against a schema it does not recognise, which is the correct behaviour
   and an unpleasant surprise if the order is wrong.
5. **Nothing is enabled.** A built environment writes nothing to a marketplace.
   Enabling that is a separate decision, described at the end.

## 1. Create the secrets

In Lockbox, in the environment's own folder:

| Secret | Holds | Read by |
| --- | --- | --- |
| `marketops-db-migration` | Migration role password | The migration runner, and nothing else |
| `marketops-db-application` | Application role password | The application |
| `marketops-ozon-<account>` | Marketplace credential | The acquisition worker |
| `marketops-wb-<account>` | Marketplace credential | The acquisition worker |
| `marketops-model-provider` | Model provider credential | The application |

Generate each value in Lockbox. A value that was generated somewhere else has
been somewhere else.

## 2. Apply the infrastructure

```bash
cd infra/yandex/environments/staging
cp terraform.tfvars.example terraform.tfvars   # fill in identifiers, no passwords
terraform init -backend-config=...
terraform plan -out=plan.bin
```

**Read the plan.** Not skim — read it. The things worth stopping for:

- any security group rule admitting a range wider than the one it names;
- `deletion_protection = false` on a production database;
- an object-storage retention shorter than the value in `variables.tf` demands;
- any resource outside `ru-central1`.

Then apply. The passwords are supplied at apply time from Lockbox, never from
the tfvars file.

## 3. Apply the migrations

As the migration identity, against the new cluster:

```bash
cd backend/marketops-server
./mvnw flyway:migrate -Dflyway.url=... -Dflyway.user=marketops_migration
```

Confirm the applied set matches the approved list. The application asserts this
at startup too, but finding out here is cheaper.

## 4. Start the application

With `marketops.acquisition.scheduler-enabled` and
`marketops.price-write.worker-enabled` both false. The environment is now
running, serving the console, and touching no marketplace.

Confirm:

```
GET /actuator/health/readiness
GET /api/v1/meta/status
```

## 5. Register the reference data

Through the loopback maintenance surface, in this order: organizations, legal
entities, marketplace accounts, stores, credentials, capabilities, endpoints,
API profiles, auth headers, capability operations. Each carries evidence and
each starts unverified.

Nothing is verified by this step. Verification is a claim that somebody checked
a real source, and it is recorded separately with the evidence reference.

## 6. Enable reading

Turn on `marketops.acquisition.scheduler-enabled`. The platform now acquires
marketplace facts and derives figures from them. It still writes nothing.

Watch the backlog for a full cycle before going further.

## 7. Enabling writes is not part of this

A platform write needs, separately and in addition: a verified write capability,
verified capability operations, both feature-flag scopes explicitly enabled, an
entity on the pilot allowlist, a live authorization, and a passing deterministic
guardrail. Each is a decision with a name attached.

None of them is implied by an environment existing, by this runbook completing,
or by any branch merging. See the Execution Envelope Policy for who may make
them.
