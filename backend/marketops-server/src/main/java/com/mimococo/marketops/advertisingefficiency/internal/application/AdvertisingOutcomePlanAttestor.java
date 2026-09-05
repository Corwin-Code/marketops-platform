package com.mimococo.marketops.advertisingefficiency.internal.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.util.HexFormat;
import java.util.Properties;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Trusted canonical computation boundary, used only by the internal planner.
 * There is no route that accepts a caller's snapshot or claims for attestation.
 * The independent issuer role has no application-role membership. A SQL caller
 * cannot use a typed-looking JSON object as proof that the computation ran.
 */
@Component
final class AdvertisingOutcomePlanAttestor {
    private static final SecureRandom RANDOM=new SecureRandom();
    private final String jdbcUrl,username,password;
    AdvertisingOutcomePlanAttestor(@Value("${marketops.identity.invocation.jdbc-url:}") String jdbcUrl,
            @Value("${marketops.identity.invocation.username:}") String username,
            @Value("${marketops.identity.invocation.password:}") String password) {
        this.jdbcUrl=jdbcUrl;this.username=username;this.password=password;
    }
    boolean available() { return !jdbcUrl.isBlank() && "marketops_identity_issuer".equals(username) && !password.isBlank(); }
    String attest(UUID baseline,UUID organization,String computedPayloadDigest,int backend,long transaction) {
        if(!available())
            throw new IllegalStateException("trusted canonical outcome planner authority is unavailable");
        byte[] random=new byte[32];RANDOM.nextBytes(random);
        String proof=HexFormat.of().formatHex(random);
        var credentials=new Properties();credentials.setProperty("user",username);credentials.setProperty("password",password);
        try(var connection=DriverManager.getConnection(jdbcUrl,credentials);
                var query=connection.prepareStatement("SELECT ops.issue_ad_outcome_plan_grant(?,?,?,?,?,?)")) {
            query.setQueryTimeout(5);
            query.setString(1,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(proof.getBytes(StandardCharsets.UTF_8))));
            query.setObject(2,baseline);query.setObject(3,organization);query.setString(4,computedPayloadDigest);
            query.setInt(5,backend);query.setLong(6,transaction);query.execute();return proof;
        } catch(java.sql.SQLException | java.security.NoSuchAlgorithmException refused) {
            // Never put credentials, database addresses or the bearer proof in logs.
            throw new IllegalStateException("trusted canonical outcome planner authority refused the request");
        }
    }
}
