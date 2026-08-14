# Browser smoke check

The automated tests render the console into a simulated document. That is enough
to establish what it does with each answer the backend can give, and it is not
enough to establish that the built bundle loads in a browser, that the request
crosses an origin boundary, or that the page is legible.

This check is performed by a person against a real browser, and its result is
recorded as evidence. It is short on purpose: five observations, each of which
has failed in some project for a reason no unit test could have caught.

## Preparation

```
make up
make backend-run
cd frontend/marketops-console && npm run build && npm run preview
```

The preview server binds `127.0.0.1:4173` and serves the built bundle, not the
development server. A development server resolves modules differently and can
hide a build defect entirely.

## Observations

| # | What to do | What must be true |
| --- | --- | --- |
| 1 | Open <http://127.0.0.1:4173> | The page renders. The heading and a platform state are visible without scrolling |
| 2 | Read the state section | It reports `ready`, and the details section names the application, environment, backend version, schema version and correlation identifier |
| 3 | Open the browser's network view and reload | Exactly one request to `/api/v1/meta/status`. It carries an `X-Correlation-ID` header, and the response carries the same value back |
| 4 | Open the browser's console | No error and no warning. In particular no content-security-policy violation, which would mean the page loaded something the policy did not allow |
| 5 | Stop the backend and press "Check again" | The state becomes `unreachable`, the details section disappears, and no platform value from the previous answer is left on the screen |

Observation 5 is the one worth being slow about. A console that keeps the last
good answer visible while the backend is down reports a healthy platform during
an outage, which is precisely when someone is relying on it.

## Recording the result

Write the outcome to
`docs/07-phase-evidence/WP-P0-001/browser-smoke.md` using this shape:

```
Date:        <date>
Commit:      <full object name of the commit that was built>
Browser:     <name and version>
Observation: 1 PASS / 2 PASS / 3 PASS / 4 PASS / 5 PASS
Notes:       <anything surprising, or "none">
```

Record the commit, not the branch. A branch moves, and evidence that names one
describes whatever it happens to point at when it is read.

No screenshot is attached. A screenshot of an operations console carries
whatever was on the screen, and this one displays an environment name, a host
and a correlation identifier.
