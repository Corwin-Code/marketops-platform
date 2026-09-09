package com.mimococo.marketops.listingconversion;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Every code the backend can emit has a Russian and a Chinese label in the
 * Console catalogue, so no operator ever reads a raw enum on a screen.
 */
class ListingConversionCodesTest {

    private static final Path CATALOGUE = Path.of("frontend", "marketops-console", "src", "listing", "i18n",
            "backend-codes.json");

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

    @Test
    @DisplayName("TC-LC-I18N-01 every family is non-empty and every code is an upper-case identifier")
    void familiesAreWellFormed() {
        Map<String, List<String>> all = ListingConversionCodes.all();

        assertThat(all).isNotEmpty();
        all.forEach((family, codes) -> {
            assertThat(codes).describedAs(family).isNotEmpty().doesNotHaveDuplicates();
            assertThat(codes).describedAs(family).allMatch(code -> code.matches("[A-Z][A-Z0-9_]*"));
        });
    }

    @Test
    @DisplayName("TC-LC-I18N-02 the Console catalogue carries zh and ru for every backend code")
    void catalogueIsComplete() throws Exception {
        Path file = repositoryRoot().resolve(CATALOGUE);
        assertThat(file).describedAs("catalogue file").exists();
        JsonNode catalogue = new ObjectMapper().readTree(Files.readString(file, StandardCharsets.UTF_8));

        List<String> missing = new ArrayList<>();
        ListingConversionCodes.all().forEach((family, codes) -> {
            JsonNode entries = catalogue.path(family);
            for (String code : codes) {
                JsonNode labels = entries.path(code);
                for (String language : List.of("zh", "ru")) {
                    if (labels.path(language).asText("").isBlank()) {
                        missing.add(family + "." + code + "." + language);
                    }
                }
            }
        });
        assertThat(missing).isEmpty();
    }
}
