# Final Closure Verification evidence package

Read `FINAL-CLOSURE-VERIFICATION.md` and `VERIFICATION-RESULT.json` first.
This package records a NOT PASS on the exact W10 candidate; it adds no frozen
finding, changes no Contract and grants no merge/production authority.

`SOURCE-EXCERPTS.md` gives exact source locations/hashes. The probes are bounded
unit/expression diagnostics only, not full PostgreSQL or HTTP reproduction.

To rerun using the original backend artifact already present in the Codex final
handoff, choose a new disposable output directory:

```bash
python3 reproduce_probes.py \
  --backend-artifact /path/to/final-handoff/raw/artifact-9974096071.zip \
  --out /tmp/marketops-controller-probes-new
```

No network access is used. The script refuses a wrong artifact digest or an
existing output directory, verifies decoded source hashes and compiles only
required source dependencies. The package does not include credentials or a
repository clone. `PACKAGE-MANIFEST.json` hashes all substantive files; the
manifest itself is excluded to avoid circular hashing. SHA256SUMS additionally
covers the manifest.
