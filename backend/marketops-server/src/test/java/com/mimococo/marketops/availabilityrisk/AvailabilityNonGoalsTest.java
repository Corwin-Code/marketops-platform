package com.mimococo.marketops.availabilityrisk;

import static org.assertj.core.api.Assertions.assertThat;

import com.mimococo.marketops.operationsworkflow.ActionKind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The things this Slice is not allowed to have built.
 *
 * <p>A non-goal that nothing checks is a non-goal that arrives by accident. The
 * Contract forbids a stock write path and a second inventory product outright,
 * and "we did not write one" is a claim about the whole source tree that only a
 * test over the whole source tree can make.
 *
 * <p>The scan is textual on purpose. A type-level rule would only see what
 * compiles today; a name appearing anywhere in the availability module or in a
 * migration is the earliest point at which somebody could be starting to build
 * one of these.
 */
class AvailabilityNonGoalsTest {

    private static final Path MODULE =
            Path.of("src/main/java/com/mimococo/marketops/availabilityrisk");
    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    /** Words that would mean somebody had started building a stock write path. */
    private static final List<String> WRITE_PATH_MARKERS = List.of(
            "STOCK_CHANGE", "stockcommand", "stock_command", "stock_outbox", "stock_readback",
            "PriceWritePort", "WritePort", "Outbox", "Readback");

    /** Words that would mean a different inventory product had been started. */
    private static final List<String> ADJACENT_PRODUCT_MARKERS = List.of(
            "dead_stock", "deadstock", "ageing_stock", "slow_moving", "slowmoving",
            "overstock", "allocation_recommendation", "stock_transfer", "transfer_order",
            "replenishment_quantity", "reorder_quantity", "latest_order_date", "stock_target");

    @Test
    @DisplayName("TC-NONGOAL-001 the availability module contains no platform write path")
    void theModuleHasNoWritePath() throws IOException {
        List<String> found = occurrences(MODULE, WRITE_PATH_MARKERS);

        assertThat(found)
                .as("this Slice calculates, records and routes; it never writes to a marketplace")
                .isEmpty();
    }

    @Test
    @DisplayName("TC-NONGOAL-002 no migration creates a stock command, outbox or readback")
    void noMigrationCreatesAStockWriteTable() throws IOException {
        List<String> found = occurrences(MIGRATIONS, List.of(
                "stock_command", "stock_command_outbox", "stock_readback", "STOCK_CHANGE"));

        assertThat(found)
                .as("a stock write table would be the first half of a capability nobody authorised")
                .isEmpty();
    }

    @Test
    @DisplayName("TC-NONGOAL-003 no adjacent inventory product has been started")
    void noAdjacentInventoryProductExists() throws IOException {
        List<String> inModule = occurrences(MODULE, ADJACENT_PRODUCT_MARKERS);
        List<String> inMigrations = occurrences(MIGRATIONS, ADJACENT_PRODUCT_MARKERS);

        assertThat(inModule).isEmpty();
        assertThat(inMigrations).isEmpty();
    }

    @Test
    @DisplayName("TC-NONGOAL-004 the workflow action vocabulary offers no stock change")
    void theActionVocabularyOffersNoStockChange() {
        assertThat(Stream.of(ActionKind.values()).map(Enum::name))
                .as("an action kind is what an approval and a command are built on")
                .doesNotContain("STOCK_CHANGE", "REPLENISH", "TRANSFER", "ALLOCATE");
    }

    /**
     * Every place one of these words appears, with its file and line.
     *
     * <p>Reported rather than counted so a failure names what to look at. A
     * boolean would tell somebody the rule broke without telling them where.
     */
    private static List<String> occurrences(Path root, List<String> markers) throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int index = 0; index < lines.size(); index++) {
                    String lowered = lines.get(index).toLowerCase(Locale.ROOT);
                    for (String marker : markers) {
                        if (lowered.contains(marker.toLowerCase(Locale.ROOT))) {
                            found.add(file + ":" + (index + 1) + " " + marker);
                        }
                    }
                }
            }
        }
        return found;
    }
}
