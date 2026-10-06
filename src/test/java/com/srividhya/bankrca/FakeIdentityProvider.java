package com.srividhya.bankrca;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.srividhya.bankrca.security.DevTokens;
import com.sun.net.httpserver.HttpServer;

/**
 * A stand-in for an identity provider on localhost: serves the discovery document and the
 * signing keys the way a real one does, and issues tokens signed with its own key.
 */
public class FakeIdentityProvider {

    private final KeyPair keys;
    private final String issuer;

    public FakeIdentityProvider() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            keys = generator.generateKeyPair();
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            issuer = "http://127.0.0.1:" + server.getAddress().getPort() + "/realms/bank";
            serve(server, "/realms/bank/.well-known/openid-configuration",
                    "{\"issuer\":\"" + issuer + "\",\"jwks_uri\":\"" + issuer + "/jwks\"}");
            serve(server, "/realms/bank/jwks",
                    new JWKSet(new RSAKey.Builder((RSAPublicKey) keys.getPublic()).keyID("k1").build()).toString());
            server.start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String issuer() {
        return issuer;
    }

    public String token(String client, String scopes) {
        return DevTokens.mint(keys, issuer, client, scopes, DevTokens.AUDIENCE, Duration.ofMinutes(5));
    }

    private static void serve(HttpServer server, String path, String body) {
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            } finally {
                exchange.close();
            }
        });
    }
}
