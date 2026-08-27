# A marketplace answer could not be stored

This is the runbook for the alert `marketops-evidence-write-failed`.

## What has happened

Acquisition received an answer from a marketplace and could not put it into the
evidence store. The run stops rather than continuing.

## Why it stops

Every fact this product derives points back at the bytes a marketplace actually
sent. A fact without those bytes is a claim, and a claim that cannot be checked
is worth less than no claim at all — it looks the same as one that can. So the
acquisition path treats custody as a precondition rather than as a side effect:
no evidence, no cursor advance, no derived fact.

This is why the alert is at alarm severity even though nothing is visibly
broken. The platform is refusing to do its job correctly, which is the right
refusal, and it will keep refusing until custody works.

## Step 1 — is it the store or the path to it?

```
GET /actuator/health/readiness
```

Then read the application log for `raw_custody_write_failed` and its
`failureClass`. The distinction that matters:

- **The store refused.** A permission, a policy or a lock. Something changed in
  the bucket configuration; see step 2.
- **The store was unreachable.** Network or service. Wait; acquisition retries
  and resumes on its own.
- **The write succeeded but the read-back did not verify.** The object was
  stored and did not come back byte-identical. Treat this as an integrity
  incident, not a transient failure; go to step 3.

## Step 2 — check what the bucket now allows

The evidence bucket admits exactly two workload identities and refuses everyone
else, refuses unencrypted transport, and holds objects under a compliance lock.
Confirm the workload identity is still one of the two:

```
yc iam service-account list --folder-id <folder>
```

A recently rotated identity that was not added to the bucket policy is the
commonest cause. Adding it back is an infrastructure change, applied through
`infra/yandex`, not a console action.

## Step 3 — a read-back that did not verify

The bytes that came back were not the bytes that went in. Do not retry the
write, and do not delete anything: under compliance lock you could not delete it
anyway, and that is deliberate.

1. Throw the kill switch for the affected platform. If evidence integrity is in
   question, so is every figure derived from it, and so is every price the
   platform might change on the strength of those figures.
2. Record the object's declared hash and length from `raw.raw_content` and the
   observed ones from the log.
3. Escalate to the Owner. This is not an operational problem; it is a question
   about whether the store can be relied on at all.

## What must never be done

Do not disable the read-back verification to clear the alert. The verification
is what makes a stored object evidence. An operator under pressure removing it
is exactly how a product ends up with a year of unverifiable history and no
record of when that started.
