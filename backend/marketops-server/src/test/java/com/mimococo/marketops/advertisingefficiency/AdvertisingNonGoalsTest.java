package com.mimococo.marketops.advertisingefficiency;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
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

    /** Advertising actions the Contract explicitly places outside this Slice. */
    private static final List<String> FORBIDDEN_ACTION_MARKERS = List.of(
            "budget_change", "budgetchange", "BUDGET_CHANGE",
            "campaign_pause", "campaignpause", "CAMPAIGN_PAUSE",
            "campaign_resume", "campaignresume", "CAMPAIGN_RESUME",
            "strategy_switch", "strategyswitch", "STRATEGY_SWITCH",
            "bidding_mode_change", "BIDDING_MODE_CHANGE",
            "negative_keyword", "NEGATIVE_KEYWORD",
            "creative_write", "CREATIVE_WRITE",
            "portfolio_reallocation", "PORTFOLIO_REALLOCATION",
            "standing_policy_automation", "STANDING_POLICY_AUTOMATION");

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
}
