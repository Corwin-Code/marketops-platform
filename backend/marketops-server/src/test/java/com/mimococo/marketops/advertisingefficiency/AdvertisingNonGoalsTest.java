package com.mimococo.marketops.advertisingefficiency;

import static org.assertj.core.api.Assertions.assertThat;

import com.mimococo.marketops.operationsworkflow.ActionKind;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Slice's non-goals, checked rather than asserted.
 *
 * <p>A Contract that lists what a Slice must not build is only worth as much as
 * the evidence that it did not. This test greps the advertising module and every
 * migration for the markers those capabilities would leave behind, so a future
 * change that quietly adds a budget write or a campaign pause fails here rather
 * than in a review that might not look.
 *
 * <p>The markers are deliberately broad. A false positive costs somebody a
 * rename; a false negative costs somebody a marketplace their product did not
 * mean to touch.
 */
class AdvertisingNonGoalsTest {

    /**
     * Provider write paths the Contract places outside this Slice.
     *
     * <p>These are deliberately the markers a <em>write path</em> would leave —
     * a capability code, an endpoint operation function, a command table — and
     * not the bare name of the business action. The Contract permits a governed
     * Manual Execution Packet to instruct a person to change a budget or pause a
     * campaign in the marketplace's own console; what it forbids is any of those
     * becoming an API path. A scan for the bare word would fail on the manual
     * packet's own vocabulary and would therefore have to be deleted, which is
     * how a non-goal check quietly stops checking anything.
     */
    private static final List<String> FORBIDDEN_ACTION_MARKERS = List.of(
            "budget_command", "budgetcommand", "BUDGET_APPLY", "BUDGET_READBACK",
            "'ad-budget", "\"ad-budget", "budget-change", "budget_write",
            "pause_command", "pausecommand", "PAUSE_APPLY", "'ad-status",
            "status-change-write", "campaign_pause_write",
            "strategy_switch", "strategyswitch", "STRATEGY_SWITCH", "STRATEGY_APPLY",
            "bidding_mode_write", "BIDDING_MODE_APPLY",
            "negative_keyword", "NEGATIVE_KEYWORD",
            "creative_write", "CREATIVE_WRITE", "CREATIVE_APPLY",
            "portfolio_reallocation", "PORTFOLIO_REALLOCATION",
            "standing_policy_automation", "STANDING_POLICY_AUTOMATION",
            "portfolio_intervention", "PORTFOLIO_INTERVENTION");

    /** Capabilities that belong to a different Slice entirely. */
    private static final List<String> ADJACENT_PRODUCT_MARKERS = List.of(
            "STOCK_CHANGE", "stock_command", "stockcommand",
            "replenishment", "REPLENISHMENT",
            "overstock", "OVERSTOCK",
            "dead_stock", "DEAD_STOCK",
            "slow_moving", "SLOW_MOVING",
            "allocation_transfer", "ALLOCATION_TRANSFER");

    /** Interaction modes the Contract prohibits outright. */
    private static final List<String> PROHIBITED_INTERACTION_MARKERS = List.of(
            "browser_automation", "BROWSER_AUTOMATION",
            "webdriver", "WebDriver",
            "scrape", "Scrape", "SCRAPING",
            "unpublished_api", "UNPUBLISHED_API");

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.exists(candidate.resolve("bootstrap-manifest.json"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException("repository root not found");
        }
        return candidate;
    }

    private static List<Path> sources() {
        Path root = repositoryRoot();
        List<Path> files = new ArrayList<>();
        collect(root.resolve(
                "backend/marketops-server/src/main/java/com/mimococo/marketops/advertisingefficiency"),
                ".java", files);
        collect(root.resolve("backend/marketops-server/src/main/resources/db/migration"),
                ".sql", files);
        return files;
    }

    private static void collect(Path directory, String suffix, List<Path> into) {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(suffix))
                    .forEach(into::add);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static List<String> hits(List<String> markers) {
        List<String> found = new ArrayList<>();
        for (Path source : sources()) {
            String content = read(source);
            for (String marker : markers) {
                // A marker inside a comment that explains the prohibition is the
                // point of the comment, so only non-comment occurrences count.
                for (String line : content.split("\n", -1)) {
                    String trimmed = line.stripLeading();
                    if (trimmed.startsWith("--") || trimmed.startsWith("*")
                            || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
                        continue;
                    }
                    if (line.contains(marker)) {
                        found.add(source.getFileName() + " -> " + marker
                                + " in: " + line.strip().toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        return found;
    }

    @Test
    @DisplayName("TC-ADV-NONGOAL-001 no advertising action outside AD_BID_CHANGE exists in source or schema")
    void onlyBidChangeExists() {
        assertThat(hits(FORBIDDEN_ACTION_MARKERS))
                .describedAs("the Contract selects AD_BID_CHANGE and only AD_BID_CHANGE")
                .isEmpty();
    }

    @Test
    @DisplayName("TC-ADV-NONGOAL-002 no adjacent inventory capability is reopened by this Slice")
    void adjacentInventoryCapabilitiesStayClosed() {
        assertThat(hits(ADJACENT_PRODUCT_MARKERS))
                .describedAs("SLICE-V1-002's deferred capabilities are not reopened here")
                .isEmpty();
    }

    @Test
    @DisplayName("TC-ADV-NONGOAL-003 no browser automation, scraping or unpublished interface appears")
    void onlyOfficialInterfacesAppear() {
        assertThat(hits(PROHIBITED_INTERACTION_MARKERS))
                .describedAs("official APIs and lawful console operation are the only permitted modes")
                .isEmpty();
    }

    @Test
    @DisplayName("TC-ADV-NONGOAL-004 the module names exactly one controlled-write direction set")
    void exactlyThreeBidDirectionsExist() {
        assertThat(BidDirection.values())
                .containsExactly(
                        BidDirection.PROTECTION_DECREASE,
                        BidDirection.OPTIMIZATION_INCREASE,
                        BidDirection.EXACT_PRIOR_BID_COMPENSATION);
    }

    /**
     * The distinction the Contract actually draws: a person may be instructed to
     * change a budget or a status in the marketplace's own console, and no code
     * path may do it.
     *
     * <p>So AD_BUDGET_CHANGE and AD_STATUS_CHANGE are permitted to appear, and
     * are permitted to appear in the original manual packet vocabulary and
     * the governed manual policy/proposal/proof migration. An occurrence elsewhere would mean
     * something other than a human instruction had learned to name them.
     */
    @Test
    @DisplayName("TC-ADV-NONGOAL-005 budget and status actions exist only as human instructions")
    void budgetAndStatusExistOnlyAsManualInstructions() {
        for (String action : List.of("AD_BUDGET_CHANGE", "AD_STATUS_CHANGE")) {
            List<Path> carrying = new ArrayList<>();
            for (Path source : sources()) {
                if (read(source).contains(action)) {
                    carrying.add(source);
                }
            }
            assertThat(carrying)
                    .describedAs("%s is restricted to the two governed human-instruction migrations", action)
                    .extracting(path -> path.getFileName().toString())
                    .containsExactlyInAnyOrder(
                            "V0039__create_advertising_target_materiality_and_manual_shadow.sql",
                            "V0060__govern_manual_proposals_packets_and_configuration_proof.sql");
        }
    }

    @Test
    @DisplayName("TC-ADV-NONGOAL-007 governed Manual instructions cannot add an executable write family")
    void governedManualActionsCannotEnterCommandOrOutbox() {
        assertThat(Arrays.stream(ActionKind.values()).filter(ActionKind::writeCapable))
                .containsExactly(ActionKind.PRICE_CHANGE, ActionKind.AD_BID_CHANGE);
        String manual = read(repositoryRoot().resolve(
                "backend/marketops-server/src/main/resources/db/migration/"
                        + "V0060__govern_manual_proposals_packets_and_configuration_proof.sql"));
        assertThat(manual).contains("CREATE TABLE core.ad_manual_policy", "CREATE TABLE ops.ad_manual_proposal",
                "CREATE FUNCTION ops.select_ad_manual_packet", "CREATE FUNCTION ops.record_ad_manual_observation");
        assertThat(manual).doesNotContainPattern(
                "(?i)INSERT\\s+INTO\\s+(?:ops|platform)\\.[a-z_]*(?:command|outbox)[a-z_]*");
    }

    /**
     * The registry describes exactly two controlled writes.
     *
     * <p>A third capability code appearing in the write-shape trigger would be a
     * third controlled write, whatever it was called.
     */
    @Test
    @DisplayName("TC-ADV-NONGOAL-006 the write registry describes exactly two capabilities")
    void writeRegistryDescribesExactlyTwoCapabilities() {
        Path registry = repositoryRoot().resolve(
                "backend/marketops-server/src/main/resources/db/migration/"
                        + "V0040__widen_write_registry_for_ad_bid_capability.sql");
        String content = read(registry);

        assertThat(content).contains("capability.capability_code = 'price-change'");
        assertThat(content).contains("capability.capability_code = 'ad-bid-change'");
        assertThat(content).contains("no write shape is defined for this capability");
        // Any other capability code named anywhere in the write-shape validation
        // would be a third controlled write, whatever it was called. Counting
        // distinct codes rather than occurrences, because the same two are
        // legitimately named more than once.
        java.util.Set<String> codes = new java.util.TreeSet<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("capability\\.capability_code = '([a-z0-9-]+)'")
                .matcher(content);
        while (matcher.find()) {
            codes.add(matcher.group(1));
        }
        assertThat(codes)
                .describedAs("the write-shape validation names exactly two capabilities")
                .containsExactly("ad-bid-change", "price-change");
    }
}
