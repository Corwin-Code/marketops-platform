#!/usr/bin/env python3
"""Generate the ignored local environment files for a developer workstation.

The generator writes only values that legitimately differ between workstations:
three database passwords and the published database port for the backend target,
and non-secret display settings for the frontend target.

Database role names are not generated. They are non-secret constants that must be
identical across every environment, so they live in checked-in configuration.

Safety contract:

* an existing file is never replaced unless ``--force`` is given interactively;
* a target that Git does not ignore is refused, so a generated password cannot be
  committed by accident;
* the file is created with owner-only permissions where the platform supports it;
* generated values are never printed, on any code path including error handling;
* only the Python standard library is used, so the generator adds no dependency.

Passwords are drawn from an alphanumeric alphabet. That keeps every value safe to
embed in a shell command, a Java properties file, YAML, and a ``psql`` literal
without escaping, which removes a whole class of quoting defects.
"""

from __future__ import annotations

import argparse
import os
import secrets
import string
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]

PASSWORD_ALPHABET = string.ascii_letters + string.digits
PASSWORD_LENGTH = 32

BACKEND_TARGET = "root"
FRONTEND_TARGET = "frontend"

TARGET_PATHS = {
    BACKEND_TARGET: REPO_ROOT / ".env.local",
    FRONTEND_TARGET: REPO_ROOT / "frontend" / "marketops-console" / ".env.local",
}

BACKEND_SECRET_VARIABLES = (
    "MARKETOPS_POSTGRES_SUPERUSER_PASSWORD",
    "MARKETOPS_DB_MIGRATION_PASSWORD",
    "MARKETOPS_DB_APP_PASSWORD",
)

DEFAULT_DB_PORT = "5432"

FRONTEND_VARIABLES = (
    ("VITE_MARKETOPS_API_BASE_URL", "http://127.0.0.1:8080"),
    ("VITE_MARKETOPS_ENVIRONMENT", "local"),
    # The operating console signs operators in against the organization's own
    # identity provider. A workstation has no such provider, so these are left
    # empty on purpose: the console then shows the platform-state panel and
    # says the operating surface is not configured here, rather than offering a
    # sign-in button that cannot work.
    ("VITE_MARKETOPS_OIDC_AUTHORIZATION_ENDPOINT", ""),
    ("VITE_MARKETOPS_OIDC_TOKEN_ENDPOINT", ""),
    ("VITE_MARKETOPS_OIDC_CLIENT_ID", ""),
    ("VITE_MARKETOPS_OIDC_AUDIENCE", ""),
    ("VITE_MARKETOPS_STORE_ID", ""),
)

BACKEND_HEADER = """# Generated local development values. Never commit this file.
# Regenerate with: make env-init
"""

FRONTEND_HEADER = """# Generated local frontend values. Never commit this file.
# Vite inlines every VITE_* variable into the built bundle, so no secret belongs here.
# Regenerate with: make env-init
"""


def generate_password() -> str:
    """Return an alphanumeric password with roughly 190 bits of entropy."""
    return "".join(secrets.choice(PASSWORD_ALPHABET) for _ in range(PASSWORD_LENGTH))


def path_is_git_ignored(path: Path) -> bool:
    """Report whether Git ignores ``path``.

    A target that Git tracks would place a generated password under version
    control, so the caller must refuse to write in that case. A repository
    without a working Git installation is treated as not ignored, which fails
    closed.
    """
    try:
        completed = subprocess.run(
            ["git", "check-ignore", "--quiet", "--", str(path)],
            cwd=str(REPO_ROOT),
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        )
    except (OSError, subprocess.SubprocessError):
        return False
    return completed.returncode == 0


def render_backend_content() -> tuple[str, list[str]]:
    """Return the backend file body and the variable names it defines."""
    lines = [BACKEND_HEADER]
    names: list[str] = []
    for name in BACKEND_SECRET_VARIABLES:
        lines.append(f"{name}={generate_password()}")
        names.append(name)
    lines.append(f"MARKETOPS_DB_PORT={DEFAULT_DB_PORT}")
    names.append("MARKETOPS_DB_PORT")
    return "\n".join(lines) + "\n", names


def render_frontend_content() -> tuple[str, list[str]]:
    """Return the frontend file body and the variable names it defines."""
    lines = [FRONTEND_HEADER]
    names: list[str] = []
    for name, value in FRONTEND_VARIABLES:
        lines.append(f"{name}={value}")
        names.append(name)
    return "\n".join(lines) + "\n", names


def write_private_file(path: Path, content: str) -> bool:
    """Create ``path`` with owner-only permissions.

    The file is created with ``O_EXCL`` and its mode in a single call so no window
    exists in which the content is readable by other users. Returns ``True`` when
    the platform applied restrictive permissions.
    """
    path.parent.mkdir(parents=True, exist_ok=True)
    flags = os.O_CREAT | os.O_EXCL | os.O_WRONLY
    descriptor = os.open(str(path), flags, 0o600)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            handle.write(content)
    except BaseException:
        os.unlink(str(path))
        raise
    return os.name == "posix"


def confirm_overwrite(path: Path) -> bool:
    """Ask for explicit confirmation before replacing an existing local file.

    A non-interactive caller is refused rather than prompted, so an automated run
    can never destroy a developer's working credentials.
    """
    if not sys.stdin.isatty():
        print(
            "env-init: refusing to overwrite in a non-interactive shell",
            file=sys.stderr,
        )
        return False
    answer = input(f"env-init: overwrite {path}? type 'yes' to confirm: ")
    return answer.strip() == "yes"


def initialise_target(target: str, force: bool) -> int:
    """Create one local environment file and report the outcome without values."""
    path = TARGET_PATHS[target]
    print(f"env-init: target = {path}")

    if not path_is_git_ignored(path):
        print(
            "env-init: FAILED — target is not ignored by Git; refusing to write",
            file=sys.stderr,
        )
        return 1
    print("env-init: gitignore check = PASS")

    if path.exists():
        if not force:
            print("env-init: file already exists; no changes made")
            return 0
        if not confirm_overwrite(path):
            print("env-init: overwrite declined; no changes made")
            return 0
        path.unlink()

    content, names = (
        render_backend_content() if target == BACKEND_TARGET else render_frontend_content()
    )
    restricted = write_private_file(path, content)

    print(f"env-init: wrote {len(names)} variables ({', '.join(names)})")
    print("env-init: file mode = 0600" if restricted else "env-init: file mode = platform default")
    print("env-init: OK")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Generate ignored local environment files.",
    )
    parser.add_argument(
        "--target",
        choices=[BACKEND_TARGET, FRONTEND_TARGET, "all"],
        default="all",
        help="which local environment file to generate",
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="replace an existing file after interactive confirmation",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    arguments = build_parser().parse_args(argv)
    targets = (
        [BACKEND_TARGET, FRONTEND_TARGET] if arguments.target == "all" else [arguments.target]
    )
    for target in targets:
        if target == FRONTEND_TARGET and not TARGET_PATHS[FRONTEND_TARGET].parent.exists():
            print("env-init: frontend project absent; skipping frontend target")
            continue
        status = initialise_target(target, arguments.force)
        if status != 0:
            return status
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
