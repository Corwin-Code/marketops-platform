# V1 AI Data and Execution Boundary

## 1. Principle

```text
Deterministic Truth
+ AI Intelligence
+ Deterministic Authorization
```

AI is a core analysis and recommendation capability, not a parallel database,
Metric Service, Commercial Policy engine or platform executor.

## 2. Input path

```text
Canonical Facts / Metrics / Evidence References
→ Approved AI Data Projection
→ Field Allowlist
→ Secret and Buyer-PII Exclusion
→ Aggregation / Pseudonymization where possible
→ Prompt Template + Context Budget
→ AI Provider Gateway
```

The projection records:

- purpose/use case;
- field list and data classification;
- source/metric/evidence references;
- period, Store/SKU scope and Freshness;
- allowed provider/model classes;
- retention and logging policy;
- owner and version.

No arbitrary SQL result or Raw payload may be sent directly to a model.

## 3. External provider boundary

- use a provider-neutral Gateway;
- record Provider, Model, region/service endpoint if applicable, contract
  eligibility and last-verified date;
- never route around regional, contractual or security restrictions;
- do not send Credential, access token, signed object URL, Buyer name/phone/full
  address, full payment information or unapproved free text;
- support provider disablement and deterministic non-AI degradation;
- do not hard-code business logic to one model vendor.

## 4. Output contract

Every AI result is structured and validated:

```text
Fact[]
  value / statement
  evidence_refs[]
  metric_definition_version
  freshness/confidence

Inference[]
  hypothesis
  supporting_fact_refs[]
  counter_evidence / alternatives
  confidence and limitations

Recommendation[]
  action capability
  proposed parameters
  expected effect
  risk and validation window
  evidence_refs[]

Unknown[]
  missing fact
  why it matters
  next evidence/action
```

An AI `Fact` can only restate a canonical fact/evidence reference. New calculations
created by the model are labeled `AI_DERIVED_EXPLORATORY` and cannot drive
high-risk execution until productized as a deterministic Metric/Rule.

## 5. Recommendation path

```text
Validated AI Recommendation
→ deterministic Data Quality/Freshness Gate
→ deterministic Commercial/Inventory/Permission Policy
→ Task / Approval / bounded Owner Policy
→ Command creation by trusted application service
```

AI never owns the approval decision, idempotency key, Outbox writer, Marketplace
Credential or Kill Switch.

## 6. Audit and reproducibility

Record:

- projection version and field set;
- source/evidence IDs rather than unrestricted payload copies;
- prompt/template version;
- Provider/Model and request time;
- structured output and validation result;
- reviewer/approval/task/command links;
- later business outcome and whether the recommendation was accepted/rejected;
- safe error and correlation ID.

Do not log raw prompts/responses when they contain data outside the approved
retention policy. Store only the governed representation required for audit.

## 7. Quality and safety tests

- field allowlist and PII/Secret negative tests;
- prompt injection/untrusted text isolation;
- structured schema validation and unknown-field refusal;
- Fact evidence-reference integrity;
- hallucinated metric/ID rejection;
- deterministic Gate cannot be bypassed by model text;
- provider timeout/retry/budget/circuit/fallback;
- model/version change regression set;
- Russian-language quality review for externally visible content;
- outcome tracking without treating correlation as causation.

## 8. V1 authority limit

V1 supports analysis, explanation and concrete recommendation. A future autonomous
operating Agent requires a new Owner Decision, risk model, ADR and Capability
Gate after the deterministic system has stable production evidence.
