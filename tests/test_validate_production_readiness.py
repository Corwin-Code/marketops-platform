from __future__ import annotations

import unittest

from scripts.validate_production_readiness import (
    FORBIDDEN_BACKEND_DEPENDENCIES,
    HISTORY_COMMENT_PATTERNS,
    IDENTIFIER_CONTEXT,
    IDENTIFIER_SCAFFOLD_TERMS,
    REQUIRED_NAMES,
    RETIRED_ARTEFACTS,
    RULE_DEFINITION_PATHS,
    SCAFFOLD_TERMS,
    UNRESOLVED_MARKERS,
    ACTION_REFERENCE,
    PATH_RESTRICTION,
    PENDING_EVIDENCE,
    action_reference_violations,
    comment_lines,
    declared_dependency_artifacts,
    matching_lines,
    runner_reference_violations,
)


class ExclusionScopeTests(unittest.TestCase):
    """The only exclusion is the rule definition itself and it cannot widen."""

    def test_exclusion_list_is_exactly_the_rule_definition(self) -> None:
        self.assertEqual(
            (
                "scripts/validate_production_readiness.py",
                "tests/test_validate_production_readiness.py",
            ),
            RULE_DEFINITION_PATHS,
        )

    def test_no_directory_is_excluded(self) -> None:
        for entry in RULE_DEFINITION_PATHS:
            with self.subTest(entry=entry):
                self.assertFalse(entry.endswith("/"))
                self.assertNotIn("*", entry)


class UnresolvedMarkerTests(unittest.TestCase):
    def test_markers_are_detected(self) -> None:
        for marker in ("TODO", "FIXME", "HACK", "XXX"):
            with self.subTest(marker=marker):
                self.assertTrue(UNRESOLVED_MARKERS.search(f"// {marker}: finish this"))

    def test_word_containing_a_marker_is_not_detected(self) -> None:
        self.assertIsNone(UNRESOLVED_MARKERS.search("the TODOS_TABLE constant"))
        self.assertIsNone(UNRESOLVED_MARKERS.search("xxxyz"))


class RepositoryContractPatternTests(unittest.TestCase):
    def test_mutable_action_reference_is_rejected(self) -> None:
        workflow = "      - uses: actions/checkout@v7\n"
        self.assertEqual([(1, "- uses: actions/checkout@v7")], action_reference_violations(workflow))

    def test_sha_requires_a_version_comment(self) -> None:
        sha = "a" * 40
        self.assertEqual(
            [(1, f"- uses: actions/checkout@{sha}")],
            action_reference_violations(f"      - uses: actions/checkout@{sha}\n"),
        )
        self.assertEqual(
            [],
            action_reference_violations(f"      - uses: actions/checkout@{sha} # v7\n"),
        )

    def test_action_pattern_reads_the_reference_and_version(self) -> None:
        match = ACTION_REFERENCE.match(f"  uses: actions/setup-node@{'b' * 40} # v6")
        self.assertIsNotNone(match)
        self.assertEqual("v6", match.group("version"))

    def test_path_avoidance_language_is_rejected(self) -> None:
        text = "move the clone to a path without spaces\nnormal relative path\n"
        self.assertEqual([(1, "move the clone to a path without spaces")], matching_lines(text, PATH_RESTRICTION))

    def test_pending_implementation_evidence_is_rejected(self) -> None:
        text = "State: PENDING_LOCAL_EXECUTION\nState: PASS\n"
        self.assertEqual([(1, "State: PENDING_LOCAL_EXECUTION")], matching_lines(text, PENDING_EVIDENCE))

    def test_floating_runner_is_rejected(self) -> None:
        self.assertEqual(
            [(2, "runs-on: ubuntu-latest")],
            runner_reference_violations("name: check\n  runs-on: ubuntu-latest\n"),
        )
        self.assertEqual(
            [],
            runner_reference_violations("  runs-on: ubuntu-24.04\n"),
        )


class CommentExtractionTests(unittest.TestCase):
    def test_block_comment_lines_are_returned(self) -> None:
        source = "\n".join(
            [
                "package com.mimococo.marketops;",
                "/**",
                " * Behaviour description.",
                " */",
                "class Example {}",
            ]
        )
        numbers = [number for number, _ in comment_lines(source, ".java")]
        self.assertEqual([2, 3, 4], numbers)

    def test_line_comment_is_returned(self) -> None:
        extracted = comment_lines("int a = 1; // explanation", ".java")
        self.assertEqual(1, len(extracted))
        self.assertIn("explanation", extracted[0][1])

    def test_code_outside_comments_is_ignored(self) -> None:
        self.assertEqual([], comment_lines('String value = "TODO";', ".java"))

    def test_double_slash_inside_a_string_is_not_a_comment(self) -> None:
        self.assertEqual([], comment_lines('String url = "https://example.invalid";', ".java"))


class HistoryCommentTests(unittest.TestCase):
    def matches(self, line: str) -> bool:
        return any(pattern.search(line) for pattern, _ in HISTORY_COMMENT_PATTERNS)

    def test_history_narration_is_rejected(self) -> None:
        for line in (
            "// WP-P0-001 introduces this",
            "// v1.2 changed this behaviour",
            "// remove later once the module exists",
            "// remove this in a future work package",
            "// for now we accept the default",
            "// temporary workaround until the adapter lands",
            "// review finding from the controller",
            "// legacy compatibility with the old path",
        ):
            with self.subTest(line=line):
                self.assertTrue(self.matches(line))

    def test_functional_wording_is_accepted(self) -> None:
        for line in (
            "// Allows validation when the protected layer has no classes.",
            "// When matching classes exist, the dependency rule is fully enforced.",
            "// Readiness includes the datasource; liveness deliberately does not.",
            "// The correlation identifier is regenerated when the inbound value is invalid.",
            "// Passwords use an alphanumeric alphabet so no escaping is required.",
        ):
            with self.subTest(line=line):
                self.assertFalse(self.matches(line))


class RetiredArtefactTests(unittest.TestCase):
    def test_every_retired_marker_states_a_reason(self) -> None:
        for marker, reason in RETIRED_ARTEFACTS:
            with self.subTest(marker=marker):
                self.assertTrue(marker)
                self.assertGreater(len(reason), 20)

    def test_known_compromises_are_listed(self) -> None:
        markers = {marker for marker, _ in RETIRED_ARTEFACTS}
        self.assertIn("ops.health_probe", markers)
        self.assertIn("Type.OPEN", markers)
        self.assertIn("failOnEmptyShould", markers)
        self.assertIn("spring-boot-starter-data-jdbc", markers)


class DeclaredDependencyTests(unittest.TestCase):
    """A banned coordinate reads differently depending on the element around it."""

    NAMESPACE = 'xmlns="http://maven.apache.org/POM/4.0.0"'

    def pom(self, body: str) -> str:
        return f'<project {self.NAMESPACE}>{body}</project>'

    def test_a_declared_dependency_is_reported(self) -> None:
        document = self.pom(
            "<dependencies><dependency>"
            "<groupId>org.springframework.boot</groupId>"
            "<artifactId>spring-boot-starter-jdbc</artifactId>"
            "</dependency></dependencies>"
        )
        self.assertEqual(
            [("org.springframework.boot", "spring-boot-starter-jdbc")],
            declared_dependency_artifacts(document),
        )

    def test_an_enforcer_exclusion_is_not_a_dependency(self) -> None:
        document = self.pom(
            "<build><plugins><plugin><configuration><rules><bannedDependencies><excludes>"
            "<exclude>org.springframework.boot:spring-boot-starter-data-jpa</exclude>"
            "</excludes></bannedDependencies></rules></configuration></plugin></plugins></build>"
        )
        self.assertEqual([], declared_dependency_artifacts(document))

    def test_a_document_without_a_namespace_is_parsed(self) -> None:
        document = (
            "<project><dependencies><dependency>"
            "<groupId>org.postgresql</groupId><artifactId>postgresql</artifactId>"
            "</dependency></dependencies></project>"
        )
        self.assertEqual([("org.postgresql", "postgresql")], declared_dependency_artifacts(document))

    def test_every_forbidden_dependency_states_a_reason(self) -> None:
        for artifact, reason in FORBIDDEN_BACKEND_DEPENDENCIES:
            with self.subTest(artifact=artifact):
                self.assertNotIn(":", artifact)
                self.assertGreater(len(reason), 10)


class ProductionNamingTests(unittest.TestCase):
    def test_required_names_are_exact(self) -> None:
        self.assertEqual("com.mimococo.marketops", REQUIRED_NAMES["java_root_package"])
        self.assertEqual("marketops-server", REQUIRED_NAMES["backend_application"])
        self.assertEqual("marketops-console", REQUIRED_NAMES["frontend_package"])
        self.assertEqual("marketops_migration", REQUIRED_NAMES["migration_role"])
        self.assertEqual("marketops_app", REQUIRED_NAMES["application_role"])
        self.assertEqual("MARKETOPS_", REQUIRED_NAMES["backend_env_prefix"])
        self.assertEqual("VITE_MARKETOPS_", REQUIRED_NAMES["frontend_env_prefix"])

    def test_scaffold_terms_do_not_overlap_production_names(self) -> None:
        for term in SCAFFOLD_TERMS + IDENTIFIER_SCAFFOLD_TERMS:
            for name in REQUIRED_NAMES.values():
                with self.subTest(term=term, name=name):
                    self.assertNotIn(term, name.lower())

    def test_identifier_context_extracts_a_declared_name(self) -> None:
        match = IDENTIFIER_CONTEXT.search('<artifactId>demo-service</artifactId>')
        self.assertIsNotNone(match)
        self.assertTrue(match.group(1).lower().startswith("demo"))

    def test_prose_use_of_example_is_not_an_identifier(self) -> None:
        self.assertIsNone(IDENTIFIER_CONTEXT.search("This is for example only"))


if __name__ == "__main__":
    unittest.main()
