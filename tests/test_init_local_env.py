from __future__ import annotations

import io
import os
import re
import string
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import mock

from scripts import init_local_env


class PasswordGenerationTests(unittest.TestCase):
    def test_password_uses_only_shell_safe_characters(self) -> None:
        allowed = set(string.ascii_letters + string.digits)
        for _ in range(50):
            self.assertTrue(set(init_local_env.generate_password()) <= allowed)

    def test_password_has_the_declared_length(self) -> None:
        self.assertEqual(
            init_local_env.PASSWORD_LENGTH, len(init_local_env.generate_password())
        )

    def test_passwords_are_not_repeated(self) -> None:
        generated = {init_local_env.generate_password() for _ in range(64)}
        self.assertEqual(64, len(generated))

    def test_password_contains_no_properties_metacharacter(self) -> None:
        for _ in range(50):
            password = init_local_env.generate_password()
            for character in ("\\", "#", "!", ":", "=", " ", "'", '"'):
                self.assertNotIn(character, password)


class ContentRenderingTests(unittest.TestCase):
    def test_backend_content_defines_the_four_local_variables(self) -> None:
        content, names = init_local_env.render_backend_content()
        self.assertEqual(
            [
                "MARKETOPS_POSTGRES_SUPERUSER_PASSWORD",
                "MARKETOPS_DB_MIGRATION_PASSWORD",
                "MARKETOPS_DB_APP_PASSWORD",
                "MARKETOPS_DB_PORT",
            ],
            names,
        )
        for name in names:
            self.assertIn(f"{name}=", content)

    def test_backend_content_defines_no_role_user_name(self) -> None:
        content, names = init_local_env.render_backend_content()
        self.assertNotIn("_USER", content)
        self.assertFalse(any(name.endswith("_USER") for name in names))

    def test_frontend_content_carries_only_public_variables(self) -> None:
        content, names = init_local_env.render_frontend_content()
        for name in names:
            self.assertTrue(name.startswith("VITE_MARKETOPS_"))
        self.assertNotIn("PASSWORD", content)

    def test_backend_content_parses_as_properties_lines(self) -> None:
        content, _ = init_local_env.render_backend_content()
        for line in content.splitlines():
            if not line or line.startswith("#"):
                continue
            self.assertRegex(line, r"^[A-Z0-9_]+=[A-Za-z0-9]+$")


class WriteProtectionTests(unittest.TestCase):
    def test_file_is_created_with_owner_only_permissions(self) -> None:
        with TemporaryDirectory() as directory:
            target = Path(directory) / "generated"
            init_local_env.write_private_file(target, "MARKETOPS_DB_PORT=5432\n")
            if os.name == "posix":
                self.assertEqual(0o600, target.stat().st_mode & 0o777)

    def test_existing_file_is_never_silently_replaced(self) -> None:
        with TemporaryDirectory() as directory:
            target = Path(directory) / "generated"
            target.write_text("original", encoding="utf-8")
            with self.assertRaises(FileExistsError):
                init_local_env.write_private_file(target, "replacement")
            self.assertEqual("original", target.read_text(encoding="utf-8"))


class IgnoreProtectionTests(unittest.TestCase):
    def test_a_tracked_target_is_refused(self) -> None:
        with mock.patch.object(init_local_env, "path_is_git_ignored", return_value=False):
            buffer = io.StringIO()
            with redirect_stdout(buffer):
                status = init_local_env.initialise_target(init_local_env.BACKEND_TARGET, False)
            self.assertEqual(1, status)

    def test_a_missing_git_installation_fails_closed(self) -> None:
        with mock.patch.object(init_local_env.subprocess, "run", side_effect=OSError):
            self.assertFalse(init_local_env.path_is_git_ignored(Path("/tmp/whatever")))


class NoSecretOutputTests(unittest.TestCase):
    def test_generated_values_are_never_printed(self) -> None:
        with TemporaryDirectory() as directory:
            target = Path(directory) / ".env.local"
            with mock.patch.dict(
                init_local_env.TARGET_PATHS, {init_local_env.BACKEND_TARGET: target}
            ), mock.patch.object(init_local_env, "path_is_git_ignored", return_value=True):
                buffer = io.StringIO()
                with redirect_stdout(buffer):
                    status = init_local_env.initialise_target(
                        init_local_env.BACKEND_TARGET, False
                    )
            self.assertEqual(0, status)
            printed = buffer.getvalue()
            written = target.read_text(encoding="utf-8")
            values = [
                line.split("=", 1)[1]
                for line in written.splitlines()
                if "=" in line and not line.startswith("#")
            ]
            secrets_written = [value for value in values if len(value) >= 16]
            self.assertEqual(3, len(secrets_written))
            for value in secrets_written:
                self.assertNotIn(value, printed)

    def test_overwrite_is_refused_without_a_terminal(self) -> None:
        with TemporaryDirectory() as directory:
            target = Path(directory) / ".env.local"
            target.write_text("MARKETOPS_DB_PORT=5432\n", encoding="utf-8")
            with mock.patch.dict(
                init_local_env.TARGET_PATHS, {init_local_env.BACKEND_TARGET: target}
            ), mock.patch.object(
                init_local_env, "path_is_git_ignored", return_value=True
            ), mock.patch.object(init_local_env.sys.stdin, "isatty", return_value=False):
                buffer = io.StringIO()
                with redirect_stdout(buffer):
                    status = init_local_env.initialise_target(
                        init_local_env.BACKEND_TARGET, True
                    )
            self.assertEqual(0, status)
            self.assertEqual("MARKETOPS_DB_PORT=5432\n", target.read_text(encoding="utf-8"))


class TemplateContractTests(unittest.TestCase):
    def test_env_example_declares_names_with_blank_assignments_only(self) -> None:
        template = (Path(init_local_env.REPO_ROOT) / ".env.example").read_text(encoding="utf-8")
        for line in template.splitlines():
            stripped = line.strip()
            if not stripped or stripped.startswith("#"):
                continue
            self.assertRegex(stripped, r"^[A-Z0-9_]+=$")

    def test_env_example_documents_exactly_the_generated_variables(self) -> None:
        template = (Path(init_local_env.REPO_ROOT) / ".env.example").read_text(encoding="utf-8")
        declared = set(re.findall(r"(?m)^([A-Z0-9_]+)=$", template))
        _, generated = init_local_env.render_backend_content()
        self.assertEqual(set(generated), declared)


if __name__ == "__main__":
    unittest.main()
