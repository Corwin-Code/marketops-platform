package com.mimococo.marketops;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The maintenance surface in an environment that has not opted into writes.
 *
 * <p>Every mutation is refused with the same stable code before any handler
 * runs, regardless of attribution, while the query surface stays available.
 * This is the posture of the base configuration, so it is asserted against a
 * running application rather than against the property alone.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("ci")
@TestPropertySource(properties = "marketops.metadata-maintenance.write-enabled=false")
class MaintenanceWriteGateApiIT {

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        var container = TestDatabase.container();
        registry.add("spring.datasource.url", container::getJdbcUrl);
        registry.add("spring.datasource.username", () -> TestDatabase.applicationRole());
        registry.add("spring.datasource.password", TestDatabase::applicationPassword);
        registry.add("spring.flyway.user", () -> TestDatabase.migrationRole());
        registry.add("spring.flyway.password", TestDatabase::migrationPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("TC-API-080 a mutation is refused even with valid attribution")
    void mutationIsRefusedRegardlessOfAttribution() throws Exception {
        mockMvc.perform(post("/api/v1/admin/metadata/organizations")
                        .header("X-Operator", "ivan.petrov")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"gated\",\"displayName\":\"Gated\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("MAINTENANCE_WRITE_DISABLED"));
    }

    @Test
    @DisplayName("TC-API-081 the query surface stays available while writes are refused")
    void queriesRemainAvailable() throws Exception {
        mockMvc.perform(get("/api/v1/admin/metadata/organizations"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/metadata/audit-events?limit=1"))
                .andExpect(status().isOk());
    }
}
