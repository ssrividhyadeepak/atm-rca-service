package com.srividhya.bankrca.security;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param mode off (no tokens; local only), dev (tokens signed with a local key) or jwt (tokens from an identity provider)
 * @param issuerUri jwt mode: the identity provider whose tokens are accepted
 * @param audience the "aud" value a token must carry to be meant for this service
 * @param resourceUrl public URL of this service, advertised to clients
 * @param devKeysDir dev mode: where the local signing key is kept
 */
@ConfigurationProperties("rca.security")
public record SecurityProps(String mode, String issuerUri, String audience, String resourceUrl, Path devKeysDir) {

    public boolean off() {
        return "off".equalsIgnoreCase(mode);
    }
}
