package com.mimococo.marketops.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The advertising module did not become a second authority.
 *
 * <p>Every one of the tables below already has exactly one writer somewhere in
 * this product. A task is written by the workflow, an approval by the approval
 * service, a command by the execution boundary, a canonical metric by the
 * analytics module, an audit event by the audit recorder. A second writer would
 * not announce itself; it would look like a convenient repository method, and
 * afterwards two pieces of code would disagree about what happened.
 *
 * <p>So this reads the SQL that the advertising module actually contains. Not
 * its imports, not its bean graph — the statement text, because a module that
 * writes a table it does not own does it with a string.
 */
class SoleAuthorityArchitectureTest {

    private static final Path SOURCE_ROOT = Path.of(
            "src/main/java/com/mimococo/marketops");

    /**
     * Tables the advertising module may read and must never write.
     *
     * <p>Each entry names the writer that owns it, because the point is not that
     * writing is forbidden but that somebody else is already doing it.
     */
    private static final Map<String, String> OWNED_ELSEWHERE = Map.ofEntries(
            Map.entry("ops.work_task", "operationsworkflow WorkTaskService"),
            Map.entry("ops.recommendation", "operationsworkflow RecommendationService"),
            Map.entry("ops.recommendation_evidence", "operationsworkflow RecommendationService"),
            Map.entry("ops.approval_decision", "operationsworkflow ApprovalService"),
            Map.entry("ops.guardrail_evaluation", "operationsworkflow GuardrailService"),
            Map.entry("ops.policy_authorization", "operationsworkflow PolicyRepository"),
            Map.entry("ops.ad_bid_command", "the ops.create_ad_bid_command function"),
            Map.entry("ops.ad_bid_command_attempt", "the ops.open_ad_bid_command_attempt function"),
            Map.entry("ops.ad_bid_command_readback",
                    "the ops.record_ad_bid_command_readback function"),
            Map.entry("ops.ad_bid_command_transition", "the ad bid transition function"),
            Map.entry("ops.price_command", "marketplaceintegration PriceCommandRepository"),
            Map.entry("raw.ad_bid_response_observation", "marketplaceintegration RawCustody"),
            Map.entry("mart.metric_value", "analyticsdecision"),
            Map.entry("mart.diagnosis_finding", "analyticsdecision"),
            Map.entry("ops.metadata_audit_event", "adminobservability MetadataAuditRecorder"));

    /**
     * Tables no Java writes at all, and the reason each has none.
     *
     * <p>These are owner-published policy: a decision bundle, an exposure
     * envelope, the versioned policies a bundle names. They are changed by a
     * reviewed data change with an owner, a reason and an evidence reference,
     * not by a service — so a repository method that inserted one would be this
     * product quietly acquiring the authority to set its own limits.
     */
    private static final Map<String, String> NO_JAVA_WRITER = Map.ofEntries(
            Map.entry("ops.ad_decision_policy_bundle", "owner-published decision authority"),
            Map.entry("core.ad_exposure_envelope", "owner-published exposure bounds"),
            Map.entry("core.ad_bid_target_policy", "owner-published step limits"),
            Map.entry("core.ad_materiality_policy", "owner-published materiality envelope"),
            Map.entry("core.ad_approval_lease_policy", "owner-published approval lease"),
            Map.entry("core.ad_outcome_policy", "owner-published outcome evaluation plan"),
            Map.entry("core.ad_human_slo_profile", "owner-published human service level"),
            Map.entry("core.ad_priority_policy", "owner-published ranking weights"),
            Map.entry("core.ad_conversion_definition", "owner-published conversion definition"),
            Map.entry("core.ad_allowable_cpa_definition", "owner-published allowable CPA"),
            Map.entry("core.ad_freshness_profile", "owner-published freshness bounds"),
            Map.entry("core.ad_optimization_qualification_policy",
                    "owner-published qualification tiers"));

    /** Statements that write. A SELECT of any of these tables is fine. */
    private static final Pattern WRITE_STATEMENT = Pattern.compile(
            "(?is)\\b(insert\\s+into|update|delete\\s+from)\\s+([a-z_]+\\.[a-z_]+)");

    @Nested
    @DisplayName("TC-AUTHORITY-001 advertisingefficiency writes nothing another module owns")
    class NoParallelWriter {

        @Test
        @DisplayName("no SQL in the module writes a table with a writer elsewhere")
        void moduleWritesNoTableOwnedElsewhere() throws IOException {
            List<String> violations = new ArrayList<>();
            for (Path file : javaFilesUnder(SOURCE_ROOT.resolve("advertisingefficiency"))) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                var matcher = WRITE_STATEMENT.matcher(source);
                while (matcher.find()) {
                    String table = matcher.group(2).toLowerCase(Locale.ROOT);
                    String owner = OWNED_ELSEWHERE.get(table);
                    if (owner != null) {
                        violations.add(file.getFileName() + " writes " + table
                                + ", which is written by " + owner);
                    }
                }
            }
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("the module reaches no other module's repository or service directly")
        void moduleReachesNoOtherModulesInternals() throws IOException {
            List<String> violations = new ArrayList<>();
            Pattern foreignInternal = Pattern.compile(
                    "import\\s+com\\.mimococo\\.marketops\\.(?!advertisingefficiency)"
                            + "([a-z]+)\\.internal\\.");
            for (Path file : javaFilesUnder(SOURCE_ROOT.resolve("advertisingefficiency"))) {
                var matcher = foreignInternal.matcher(
                        Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    violations.add(file.getFileName() + " imports "
                            + matcher.group(1) + ".internal");
                }
            }
            assertThat(violations).isEmpty();
        }
    }

    @Nested
    @DisplayName("TC-AUTHORITY-003 owner-published policy has no writer in this product")
    class OwnerPublishedPolicy {

        @Test
        @DisplayName("no Java anywhere writes a policy or bundle the Owner publishes")
        void noJavaWritesOwnerPublishedPolicy() throws IOException {
            List<String> violations = new ArrayList<>();
            for (Path file : javaFilesUnder(SOURCE_ROOT)) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                var matcher = WRITE_STATEMENT.matcher(source);
                while (matcher.find()) {
                    String table = matcher.group(2).toLowerCase(Locale.ROOT);
                    String why = NO_JAVA_WRITER.get(table);
                    if (why != null) {
                        violations.add(file.getFileName() + " writes " + table + " (" + why + ")");
                    }
                }
            }
            // A bundle is the authority a controlled write rests on. A service
            // that could write one could grant itself the authority to act,
            // which is the whole thing the whole-combination validation exists
            // to stop somebody doing by hand.
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("the bundle's own activation rule lives in the database, not in Java")
        void bundleActivationIsADatabaseRule() throws IOException {
            Path migrations = Path.of("src/main/resources/db/migration");
            String v0041 = Files.readString(
                    migrations.resolve(
                            "V0041__create_advertising_containment_and_decision_bundle.sql"),
                    StandardCharsets.UTF_8);

            // Validation is a function and a trigger, so a bundle that did not
            // validate cannot be ACTIVE however it was inserted.
            assertThat(v0041)
                    .contains("ops.ad_bundle_validation_failures")
                    .contains("AFTER INSERT OR UPDATE ON ops.ad_decision_policy_bundle");
        }
    }

    @Nested
    @DisplayName("TC-AUTHORITY-002 command creation has exactly one route")
    class OneCreationRoute {

        @Test
        @DisplayName("no Java anywhere inserts into ops.ad_bid_command")
        void noJavaInsertsCommandsDirectly() throws IOException {
            List<String> violations = new ArrayList<>();
            for (Path file : javaFilesUnder(SOURCE_ROOT)) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                var matcher = WRITE_STATEMENT.matcher(source);
                while (matcher.find()) {
                    String table = matcher.group(2).toLowerCase(Locale.ROOT);
                    if (table.startsWith("ops.ad_bid_command")) {
                        violations.add(file.getFileName() + " " + matcher.group(1)
                                + " " + table);
                    }
                }
            }
            // Every state move is a SECURITY DEFINER function the application
            // role may call and no table the application role may write. A single
            // INSERT here would be the bypass this whole design exists to remove.
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("the migration defines exactly one function that inserts a command")
        void exactlyOneFunctionCreatesCommands() throws IOException {
            Path migrations = Path.of("src/main/resources/db/migration");
            List<String> creators = new ArrayList<>();
            try (Stream<Path> files = Files.list(migrations)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".sql")).toList()) {
                    String sql = Files.readString(file, StandardCharsets.UTF_8);
                    if (sql.matches("(?is).*insert\\s+into\\s+ops\\.ad_bid_command\\s*\\(.*")) {
                        creators.add(file.getFileName().toString());
                    }
                }
            }
            assertThat(creators).containsExactly("V0045__create_ad_bid_command_from_approval.sql");
        }
    }

    private static List<Path> javaFilesUnder(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }
}
