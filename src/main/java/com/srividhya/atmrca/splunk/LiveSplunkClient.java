package com.srividhya.atmrca.splunk;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.srividhya.atmrca.config.RcaProperties;
import com.srividhya.atmrca.config.RcaProperties.Splunk;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Splunk over its REST API (management port, usually 8089). Each named search is an SPL
 * template from configuration (rca.splunk.searches); arguments are validated before they are
 * put into it. A template can also call a saved search: {@code | savedsearch "name" arg=$arg$}.
 * What can be searched is decided by the role of the Splunk account behind the credentials.
 */
@Component
@ConditionalOnProperty(name = "rca.splunk.mode", havingValue = "live")
public class LiveSplunkClient implements SplunkClient {

    private static final Logger log = LoggerFactory.getLogger(LiveSplunkClient.class);
    // Letters, digits and a few separators: nothing that can close a quote or start a new SPL command
    private static final Pattern SAFE_ARGUMENT = Pattern.compile("[A-Za-z0-9 _./:-]{1,128}");

    private final RestClient rest;
    private final JsonMapper json = new JsonMapper();
    private final URI baseUrl;
    private final String index;
    private final int maxRows;
    private final Map<String, String> searches;

    public LiveSplunkClient(RcaProperties props) {
        Splunk splunk = props.splunk();
        this.baseUrl = baseUrl(splunk.baseUrl());
        this.index = requireSafe("index", splunk.index());
        this.maxRows = splunk.maxRows();
        this.searches = splunk.searches() == null ? Map.of() : splunk.searches();
        if (!searches.containsKey(FAILED_TRANSACTIONS)) {
            throw new IllegalStateException("rca.splunk.searches." + FAILED_TRANSACTIONS + " is not configured");
        }
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(splunk.timeout() == null ? Duration.ofSeconds(60) : splunk.timeout());
        this.rest = RestClient.builder().baseUrl(baseUrl.toString()).requestFactory(factory)
                .defaultHeader("Authorization", authorization(splunk)).build();
    }

    @Override
    public List<Map<String, Object>> search(String name, Instant earliest, Instant latest, Map<String, Object> args) {
        String template = searches.get(name);
        if (template == null) {
            throw new IllegalArgumentException("Unknown search: " + name);
        }
        String spl = template.replace("$index$", index);
        for (Map.Entry<String, Object> arg : args.entrySet()) {
            spl = spl.replace("$" + arg.getKey() + "$", render(arg.getKey(), arg.getValue()));
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("search", spl.strip());
        form.add("earliest_time", epochSeconds(earliest));
        form.add("latest_time", epochSeconds(latest));
        form.add("exec_mode", "oneshot");
        form.add("output_mode", "json");
        form.add("count", String.valueOf(maxRows));

        long start = System.nanoTime();
        String body;
        try {
            body = rest.post().uri("/services/search/jobs").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().body(String.class);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            throw new IllegalStateException(status == 401
                    ? "Splunk rejected the credentials (401). Check SPLUNK_TOKEN, or SPLUNK_USERNAME and SPLUNK_PASSWORD"
                    : status == 403
                            ? "The Splunk account is not allowed to run this search (403). Check its role and index access"
                            : "Splunk search " + name + " failed with status " + status);
        } catch (ResourceAccessException e) {
            throw new IllegalStateException("Could not reach Splunk at " + baseUrl + " ("
                    + e.getMostSpecificCause().getClass().getSimpleName() + "). Check SPLUNK_URL and the network path");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode result : json.readTree(body).path("results")) {
            rows.add(row(result));
        }
        log.debug("Splunk search {} returned {} rows in {}ms", name, rows.size(), (System.nanoTime() - start) / 1_000_000);
        return rows;
    }

    @Override
    public String description() {
        return "Splunk " + baseUrl + " index " + index;
    }

    /** A list becomes "a","b","c" for use inside IN (...); every value is validated on its own. */
    private static String render(String name, Object value) {
        if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                throw new IllegalArgumentException("Search argument '" + name + "' is empty");
            }
            return list.stream().map(v -> "\"" + requireSafe(name, String.valueOf(v)) + "\"")
                    .collect(Collectors.joining(","));
        }
        return requireSafe(name, String.valueOf(value));
    }

    /** Splunk returns every value as a string, and a multi-line value as an array of lines. */
    private static Map<String, Object> row(JsonNode result) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> field : result.properties()) {
            JsonNode value = field.getValue();
            String text;
            if (value.isArray()) {
                List<String> parts = new ArrayList<>();
                value.forEach(v -> parts.add(v.asString()));
                text = String.join("\n", parts);
            } else {
                text = value.asString();
            }
            row.put(field.getKey(), "ts".equals(field.getKey()) ? toIso(text) : text);
        }
        return row;
    }

    /** Times come back as epoch seconds from eval; anything else is passed through. */
    private static String toIso(String time) {
        try {
            return Instant.ofEpochMilli(Math.round(Double.parseDouble(time) * 1000)).toString();
        } catch (NumberFormatException e) {
            return time;
        }
    }

    private static String epochSeconds(Instant t) {
        return "%d.%03d".formatted(t.getEpochSecond(), t.getNano() / 1_000_000);
    }

    private static String requireSafe(String name, String value) {
        if (value == null || !SAFE_ARGUMENT.matcher(value).matches()) {
            throw new IllegalArgumentException("Search argument '" + name + "' has characters that are not allowed");
        }
        return value;
    }

    private static URI baseUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("rca.splunk.mode is live but SPLUNK_URL is not set. Use the Splunk "
                    + "management URL, e.g. https://splunk.example.com:8089");
        }
        URI uri = URI.create(url.strip().replaceAll("/+$", ""));
        boolean local = "localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost());
        if (!"https".equals(uri.getScheme()) && !local) {
            throw new IllegalStateException("SPLUNK_URL must use https: credentials are sent with every search");
        }
        return uri;
    }

    private static String authorization(Splunk splunk) {
        if (hasText(splunk.token())) {
            return "Bearer " + splunk.token().strip();
        }
        if (hasText(splunk.username()) && hasText(splunk.password())) {
            return "Basic " + Base64.getEncoder()
                    .encodeToString((splunk.username() + ":" + splunk.password()).getBytes(StandardCharsets.UTF_8));
        }
        throw new IllegalStateException("rca.splunk.mode is live but no Splunk credentials are set. "
                + "Set SPLUNK_TOKEN (preferred), or SPLUNK_USERNAME and SPLUNK_PASSWORD");
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
