package com.srividhya.bankrca.security;

import java.security.interfaces.RSAPublicKey;
import java.util.List;

import jakarta.servlet.DispatcherType;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Three modes (rca.security.mode):
 * - off: no tokens. Allowed only without the prod profile and only while the service listens
 *   on this machine alone.
 * - dev: every request needs a bearer token signed with the local dev key (./gradlew -q devToken).
 * - jwt: every request needs a bearer token from the configured identity provider. The prod
 *   profile always uses this.
 *
 * With dev and jwt the service is an OAuth2 resource server: the token's signature, expiry,
 * issuer and audience are checked, and each endpoint needs a scope.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityProps props, Environment env,
            @Value("${server.address:127.0.0.1}") String bindAddress) throws Exception {
        // No cookies or browser sessions: the bearer token is the only credential
        http.csrf(csrf -> csrf.disable()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        if (props.off()) {
            if (env.acceptsProfiles(Profiles.of("prod"))) {
                throw new IllegalStateException("rca.security.mode=off is not allowed with the prod profile");
            }
            if (!List.of("127.0.0.1", "localhost", "::1").contains(bindAddress)) {
                throw new IllegalStateException("rca.security.mode=off is only allowed while the service listens on "
                        + "this machine (RCA_BIND_ADDRESS=127.0.0.1). Set RCA_SECURITY_MODE to dev or jwt");
            }
            return http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll()).build();
        }

        String issuer = "dev".equalsIgnoreCase(props.mode()) ? DevKeys.ISSUER : issuer(props);
        return http
                .authorizeHttpRequests(auth -> auth
                        // An error raised after authorization (a bad request body, say) is rendered by an
                        // internal forward; without this it would be reported as 403 instead of what it is
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health/**", "/.well-known/oauth-protected-resource/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/failures").hasAuthority(scope(Scopes.LOGS_READ))
                        .requestMatchers(HttpMethod.POST, "/api/runs", "/api/rca", "/api/rca/replay", "/api/knowledge/reload")
                        .hasAuthority(scope(Scopes.RCA_WRITE))
                        .requestMatchers(HttpMethod.GET, "/api/runs", "/api/correlation", "/api/rca", "/api/rca/**")
                        .hasAuthority(scope(Scopes.RCA_READ))
                        .requestMatchers(HttpMethod.GET, "/api/knowledge", "/api/knowledge/**").hasAuthority(scope(Scopes.KB_READ))
                        .requestMatchers(HttpMethod.POST, "/api/source/locate").hasAuthority(scope(Scopes.CODE_READ))
                        // Any valid token may list tools and ask; each tool call is checked for its own scope
                        .requestMatchers("/api/tools", "/api/tools/**", "/api/assistant/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(rs -> rs
                        .jwt(Customizer.withDefaults())
                        // RFC 9728: tells a client where to get a token and which scopes exist
                        .protectedResourceMetadata(prm -> prm.protectedResourceMetadataCustomizer(metadata -> {
                            metadata.resource(props.resourceUrl()).authorizationServer(issuer)
                                    .tlsClientCertificateBoundAccessTokens(false);
                            Scopes.ALL.forEach(metadata::scope);
                        })))
                .build();
    }

    /** Tokens from the organisation's identity provider, checked against its published keys. */
    @Bean
    @ConditionalOnProperty(name = "rca.security.mode", havingValue = "jwt")
    JwtDecoder jwtDecoder(SecurityProps props) {
        String issuer = issuer(props);
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(issuer).build();
        decoder.setJwtValidator(validator(issuer, props.audience()));
        return decoder;
    }

    /** Tokens signed with the local dev key (see DevTokens). */
    @Bean
    @ConditionalOnProperty(name = "rca.security.mode", havingValue = "dev")
    JwtDecoder devJwtDecoder(SecurityProps props) {
        RSAPublicKey key = (RSAPublicKey) DevKeys.loadOrCreate(props.devKeysDir()).getPublic();
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(key).build();
        decoder.setJwtValidator(validator(DevKeys.ISSUER, props.audience()));
        return decoder;
    }

    /** Signature and expiry are always checked; this adds issuer and audience, so a token for another service is refused. */
    private static OAuth2TokenValidator<Jwt> validator(String issuer, String audience) {
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD, aud -> aud != null && aud.contains(audience)));
    }

    private static String scope(String scope) {
        return "SCOPE_" + scope;
    }

    private static String issuer(SecurityProps props) {
        if (!"jwt".equalsIgnoreCase(props.mode())) {
            throw new IllegalStateException("rca.security.mode must be off, dev or jwt, not '" + props.mode() + "'");
        }
        String issuer = props.issuerUri();
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException("rca.security.mode is jwt but RCA_ISSUER_URI is not set: the identity "
                    + "provider whose tokens this service accepts, e.g. https://login.example.com/realms/bank");
        }
        return issuer;
    }
}
