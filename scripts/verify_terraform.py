#!/usr/bin/env python3
"""Reproducible schema validation and synthetic plan evidence, never apply."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

from validate_terraform_plan import inspect

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--terraform", default="terraform")
    parser.add_argument("--provider-mirror", type=Path,
                        help="Optional existing filesystem mirror; init still enforces the immutable lockfile checksums.")
    parser.add_argument("--output", type=Path, default=ROOT / "build/terraform-evidence")
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    cli_config = output / "verification.tfrc"
    if args.provider_mirror:
        mirror=args.provider_mirror.resolve()
        if not mirror.is_dir():
            raise ValueError("The provider mirror must already exist")
        cli_config.write_text("disable_checkpoint = true\nprovider_installation {\n  filesystem_mirror {\n    path = "
                              + json.dumps(str(mirror)) + '\n    include = ["registry.terraform.io/yandex-cloud/yandex"]\n  }\n}\n')
    else:
        cli_config.write_text("disable_checkpoint = true\nprovider_installation {\n  direct {}\n}\n")
    # Never inherit cloud tokens, service-account key paths, backend credentials,
    # environment proxy credentials, or the user's Terraform CLI configuration.
    environment = {key: value for key, value in os.environ.items() if key in {"PATH", "HOME", "LANG", "TMPDIR", "SYSTEMROOT", "SSL_CERT_FILE"}}
    environment.update(TF_CLI_CONFIG_FILE=str(cli_config), TF_IN_AUTOMATION="1", CHECKPOINT_DISABLE="1")

    def run(name, arguments):
        path = output / name
        with path.open("w") as log:
            result = subprocess.run([args.terraform] + arguments, cwd=ROOT, env=environment, stdout=log, stderr=subprocess.STDOUT, timeout=300)
        if result.returncode:
            raise RuntimeError("Terraform verification failed; inspect " + str(path))
        return path

    version = json.loads(run("version.json", ["version", "-json"]).read_text())
    if version["terraform_version"] != "1.14.9":
        raise ValueError("The reviewed Terraform runtime is exactly 1.14.9")
    run("fmt.log", ["fmt", "-check", "-recursive", "infra/yandex"])
    evidence = []
    for name in ["bootstrap", "staging", "production"]:
        directory = "infra/yandex/" + ("bootstrap" if name == "bootstrap" else "environments/" + name)
        tests = list((ROOT / directory / "tests").glob("*.tftest.hcl"))
        if not tests:
            raise ValueError("A complete mock-plan test is required for " + name)
        for test in tests:
            text = test.read_text()
            runs = re.findall(r'\brun\s+"[^\"]+"\s*\{', text)
            commands = re.findall(r"\bcommand\s*=\s*(\w+)", text)
            if not re.search(r'\bmock_provider\s+"yandex"', text) or re.search(r'(?m)^\s*provider\s+"', text) or commands != ["plan"] * len(runs):
                raise ValueError("Every test must use the mock provider and explicit plan command")
        run(name + "-init.log", ["-chdir=" + directory, "init", "-backend=false", "-input=false", "-lockfile=readonly", "-no-color"])
        run(name + "-validate.log", ["-chdir=" + directory, "validate", "-no-color"])
        plan = run(name + "-plan.jsonl", ["-chdir=" + directory, "test", "-json", "-verbose"])
        evidence.append(inspect(plan, name))
    locked = {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
              for path in (ROOT / "infra/yandex").rglob(".terraform.lock.hcl") if ".terraform" not in path.parts}
    report = {"terraform": version["terraform_version"], "provider": "yandex-cloud/yandex 0.220.0", "plans": evidence,
              "provider_installation": "LOCKFILE_VERIFIED_LOCAL_MIRROR" if args.provider_mirror else "LOCKFILE_VERIFIED_REGISTRY",
              "lockfiles": locked, "real_provider_api_calls": "NONE_MOCK_PROVIDER", "deployment": "NOT_AUTHORIZED",
              "state_inspection_scope": "WRITE_ONLY_SCHEMA_AND_SYNTHETIC_PLAN_VALUES_NO_REAL_STATE"}
    (output / "summary.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
