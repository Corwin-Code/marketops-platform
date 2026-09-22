package com.mimococo.marketops.listingconversion.internal.web;

import com.mimococo.marketops.listingconversion.ListingConversionCodes;
import com.mimococo.marketops.shared.ConsoleApi;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Every code the listing conversion Console may have to render, by family.
 *
 * <p>The frontend catalogue is checked against this list, so a code added on
 * the backend without a Russian and Chinese label fails a test rather than
 * showing up raw on a screen.
 */
@RestController
@ConsoleApi
@RequestMapping("/api/v1/console/listing/catalogue")
class ListingCatalogueController {

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    Map<String, List<String>> codes() {
        return ListingConversionCodes.all();
    }
}
