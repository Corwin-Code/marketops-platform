package com.mimococo.marketops.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.mimococo.marketops.advertisingefficiency.AdConfidence;
import com.mimococo.marketops.advertisingefficiency.AdEvidenceState;
import com.mimococo.marketops.advertisingefficiency.AdObjectKind;
import com.mimococo.marketops.advertisingefficiency.AdvertisingLane;
import com.mimococo.marketops.advertisingefficiency.BidDirection;
import com.mimococo.marketops.advertisingefficiency.CandidateBasis;
import com.mimococo.marketops.advertisingefficiency.ProtectionTier;
import com.mimococo.marketops.advertisingefficiency.SaleStage;
import com.mimococo.marketops.marketplaceintegration.AdBidCommandState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Java's vocabulary and the schema's vocabulary are the same words.
 *
 * <p>This test exists because they were not. The proposal service wrote a
 * {@code candidate_basis} of {@code MAX_CPC_DERIVED} while the schema admits
 * {@code MAX_CPC_BOUNDED} and {@code CAUSE_BOUND_PROTECTION_STEP}. Nothing
 * caught it: the value type-checked, the column is {@code text}, and the failure
 * would have arrived as a constraint violation the first time a real candidate
 * was generated.
 *
 * <p>The class of mistake is general — a string that names a state, read by a
 * check constraint written somewhere else — so the guard is general. Every
 * vocabulary an enum mirrors is compared against the CHECK list that governs its
 * column, and the two must agree exactly. Not "the enum is a subset": a value
 * the database allows and Java cannot produce is a state nothing can ever reach,
 * which is its own kind of defect.
 */
class SchemaVocabularyAgreementTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    private static String allMigrations;

    @BeforeAll
    static void readMigrations() throws IOException {
        StringBuilder combined = new StringBuilder();
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".sql")).sorted().toList()) {
                combined.append(Files.readString(file, StandardCharsets.UTF_8)).append('\n');
            }
        }
        allMigrations = combined.toString();
    }

    /** One named constraint whose allowed values an enum is supposed to mirror. */
    private record Pinned(String constraint, Set<String> javaValues, String note) {
    }

    private static Set<String> namesOf(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    @Test
    @DisplayName("TC-VOCAB-001 every mirrored vocabulary agrees with its check constraint exactly")
    void mirroredVocabulariesAgree() {
        // Pinned by constraint name rather than by column name. Several tables
        // in this product have a column called "lane" or "confidence_state" and
        // they do not mean the same thing; matching on the column would compare
        // the advertising vocabulary against the availability one.
        List<Pinned> pinned = List.of(
                new Pinned("ad_bid_candidate_basis_ck", namesOf(CandidateBasis.values()),
                        "what a bid target was derived from"),
                new Pinned("ad_case_lane_ck", namesOf(AdvertisingLane.values()),
                        "which queue a case sits in"),
                new Pinned("ad_case_protection_tier_value_ck", namesOf(ProtectionTier.values()),
                        "how urgent a protection case is"),
                new Pinned("ad_case_confidence_ck", namesOf(AdConfidence.values()),
                        "how much a calculation may be trusted"),
                new Pinned("ad_native_object_kind_ck", namesOf(AdObjectKind.values()),
                        "what kind of advertising object this is"));

        SoftAssertions softly = new SoftAssertions();
        for (Pinned entry : pinned) {
            Set<String> inSchema = allowedValuesInConstraint(entry.constraint());
            softly.assertThat(inSchema)
                    .describedAs("%s (%s): no CHECK ... IN list found in any migration",
                            entry.constraint(), entry.note())
                    .isNotEmpty();
            if (!inSchema.isEmpty()) {
                softly.assertThat(inSchema)
                        .describedAs("%s (%s)", entry.constraint(), entry.note())
                        .containsExactlyInAnyOrderElementsOf(entry.javaValues());
            }
        }
        softly.assertAll();
    }

    @Test
    @DisplayName("TC-VOCAB-002 the bid direction enum is the schema's direction vocabulary")
    void directionAgrees() {
        // Direction appears on several tables. Every one of them must admit the
        // same three, because a direction one table accepts and another refuses
        // is a command that can be created and never executed.
        Set<String> expected = namesOf(BidDirection.values());
        for (String constraint : List.of(
                "ad_bid_candidate_direction_ck",
                "ad_bid_command_direction_ck",
                "ad_action_reservation_direction_ck",
                "ad_decision_policy_bundle_direction_ck")) {
            Set<String> inSchema = allowedValuesInConstraint(constraint);
            assertThat(inSchema)
                    .describedAs("%s", constraint)
                    .isNotEmpty()
                    .containsAll(expected);
        }
    }

    @Test
    @DisplayName("TC-VOCAB-003 the command state enum is the schema's state vocabulary")
    void commandStateAgrees() {
        assertThat(allowedValuesInConstraint("ad_bid_command_state_ck"))
                .containsExactlyInAnyOrderElementsOf(namesOf(AdBidCommandState.values()));
    }

    @Test
    @DisplayName("TC-VOCAB-004 the evidence-state enum is the schema's evidence vocabulary")
    void evidenceStateAgrees() {
        assertThat(allowedValuesInConstraint("ad_case_evidence_state_ck"))
                .containsExactlyInAnyOrderElementsOf(namesOf(AdEvidenceState.values()));
    }

    @Test
    @DisplayName("TC-VOCAB-005 every vocabulary value bound to a SQL parameter exists in the schema")
    void boundVocabularyValuesExistInTheSchema() throws IOException {
        // Precise where the previous net was wide: only literals actually bound
        // to a parameter whose name is a vocabulary column. That is exactly the
        // path the MAX_CPC_DERIVED defect took — a well-formed string handed to
        // a column governed by a CHECK written somewhere else.
        Pattern boundLiteral = Pattern.compile(
                "\\.param\\(\"(" + String.join("|", VOCABULARY_PARAMETERS)
                        + ")\", ?\"([A-Z][A-Z0-9_]*)\"\\)");
        Set<String> unknown = new LinkedHashSet<>();
        for (Path root : List.of(
                Path.of("src/main/java/com/mimococo/marketops/advertisingefficiency"),
                Path.of("src/main/java/com/mimococo/marketops/marketplaceintegration"),
                Path.of("src/main/java/com/mimococo/marketops/operationsworkflow"))) {
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                    Matcher matcher = boundLiteral.matcher(
                            Files.readString(file, StandardCharsets.UTF_8));
                    while (matcher.find()) {
                        if (!allMigrations.contains("\'" + matcher.group(2) + "\'")) {
                            unknown.add(file.getFileName() + ": " + matcher.group(1)
                                    + " = " + matcher.group(2));
                        }
                    }
                }
            }
        }
        assertThat(unknown).isEmpty();
    }

    /**
     * Parameter names that carry a value some CHECK constraint governs.
     *
     * <p>Binding a value to one of these is binding it to a vocabulary. Anything
     * bound here that no migration mentions is a word this product invented for
     * a column that will refuse it.
     */
    private static final List<String> VOCABULARY_PARAMETERS = List.of(
            "basis", "candidateBasis", "direction", "lane", "stage", "outcomeStage",
            "saleStage", "kind", "containmentKind", "interventionKind", "scopeKind",
            "causeClass", "actionKind", "state", "verdict", "guardState", "grade",
            "evidenceGrade", "conflictState", "purpose", "nativeObjectKind", "capabilityCode",
            "condition", "matchState", "outcomeClass");

    /** Every value any {@code CHECK ... IN (...)} on a column of this name admits. */
    private static Set<String> allowedValuesFor(String column) {
        Set<String> values = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile(
                        Pattern.quote(column) + "\\s+IN\\s*\\(([^)]*)\\)", Pattern.DOTALL)
                .matcher(allMigrations);
        while (matcher.find()) {
            values.addAll(literalsIn(matcher.group(1)));
        }
        return values;
    }

    /** Every value one named constraint admits. */
    private static Set<String> allowedValuesInConstraint(String constraintName) {
        Matcher matcher = Pattern.compile(
                        "CONSTRAINT\\s+" + Pattern.quote(constraintName)
                                + "\\s+CHECK\\s*\\(([^;]*?)\\)\\s*[,)]", Pattern.DOTALL)
                .matcher(allMigrations);
        Set<String> values = new LinkedHashSet<>();
        while (matcher.find()) {
            values.addAll(literalsIn(matcher.group(1)));
        }
        return values;
    }

    private static Set<String> literalsIn(String fragment) {
        Set<String> values = new LinkedHashSet<>();
        Matcher literals = Pattern.compile("'([A-Z][A-Z0-9_]*)'").matcher(fragment);
        while (literals.find()) {
            values.add(literals.group(1));
        }
        return values;
    }
}
