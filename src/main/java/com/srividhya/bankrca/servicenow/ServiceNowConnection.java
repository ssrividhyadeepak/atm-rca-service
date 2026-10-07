package com.srividhya.bankrca.servicenow;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.srividhya.bankrca.incident.IncidentProps.ServiceNow;

/**
 * The one place the connection to ServiceNow is set up: the instance address, https, and
 * the credentials. ServiceNow's Table API is plain REST - one URL per table,
 * /api/now/table/&lt;table&gt; - so everything that talks to it shares this client.
 */
public record ServiceNowConnection(URI instance, RestClient rest) {

    /**
     * @param why the setting that asked for ServiceNow, for the error message, e.g. "rca.incident.mode is servicenow"
     * @throws IllegalStateException when the address or the credentials are missing
     */
    public static ServiceNowConnection open(ServiceNow sn, String why) {
        if (sn == null || sn.instanceUrl() == null || sn.instanceUrl().isBlank()) {
            throw new IllegalStateException(why + " but SERVICENOW_URL is not set, e.g. https://yourbank.service-now.com");
        }
        URI instance = URI.create(sn.instanceUrl().strip().replaceAll("/+$", ""));
        boolean local = "localhost".equals(instance.getHost()) || "127.0.0.1".equals(instance.getHost());
        if (!"https".equals(instance.getScheme()) && !local) {
            throw new IllegalStateException("SERVICENOW_URL must use https: credentials are sent with every call");
        }
        String authorization;
        if (hasText(sn.token())) {
            authorization = "Bearer " + sn.token().strip();
        } else if (hasText(sn.username()) && hasText(sn.password())) {
            authorization = "Basic " + Base64.getEncoder()
                    .encodeToString((sn.username() + ":" + sn.password()).getBytes(StandardCharsets.UTF_8));
        } else {
            throw new IllegalStateException(why + " but no credentials are set. Set "
                    + "SERVICENOW_USERNAME and SERVICENOW_PASSWORD, or SERVICENOW_TOKEN");
        }
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(sn.timeout() == null ? Duration.ofSeconds(30) : sn.timeout());
        return new ServiceNowConnection(instance, RestClient.builder().baseUrl(instance.toString()).requestFactory(factory)
                .defaultHeader("Authorization", authorization).defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build());
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
