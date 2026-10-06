package com.srividhya.bankrca.security;

import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Mints a bearer token for rca.security.mode=dev, the way an identity provider would for a
 * real client: {@code ./gradlew -q devToken}, or
 * {@code ./gradlew -q devToken --args="me 'rca:read rca:write kb:read' 8"} (client, scopes, hours).
 */
public final class DevTokens {

    public static final String AUDIENCE = "bank-rca-service";

    private DevTokens() {
    }

    public static void main(String[] args) {
        String client = args.length > 0 ? args[0] : "dev-client";
        String scopes = args.length > 1 ? args[1] : Scopes.READ_ONLY;
        int hours = args.length > 2 ? Integer.parseInt(args[2]) : 8;
        String dir = System.getenv().getOrDefault("RCA_DEV_KEYS_DIR", "data/dev-keys");
        KeyPair keys = DevKeys.loadOrCreate(Path.of(dir));
        System.out.println(mint(keys, DevKeys.ISSUER, client, scopes, AUDIENCE, Duration.ofHours(hours)));
    }

    public static String mint(KeyPair keys, String issuer, String client, String scopes, String audience,
            Duration validFor) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(client)
                .audience(audience)
                .claim("scope", scopes)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(validFor)))
                .jwtID(UUID.randomUUID().toString())
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        try {
            jwt.sign(new RSASSASigner(keys.getPrivate()));
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot sign dev token", e);
        }
        return jwt.serialize();
    }
}
