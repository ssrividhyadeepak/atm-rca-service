package com.srividhya.bankrca;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A stand-in for ServiceNow's incident table API on localhost: keeps what it is sent and numbers it. */
public class FakeServiceNow {

    private final HttpServer server;
    private final JsonMapper json = new JsonMapper();
    /** The incidents "created", as the request bodies received. */
    public final List<JsonNode> incidents = new CopyOnWriteArrayList<>();
    public final List<String> requests = new CopyOnWriteArrayList<>();
    public volatile String authorization;
    /** The status to answer a create with; 201 creates. */
    public volatile int createStatus = 201;

    public FakeServiceNow() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/api/now/table/incident", exchange -> {
            authorization = exchange.getRequestHeaders().getFirst("Authorization");
            String query = exchange.getRequestURI().getQuery();
            requests.add(exchange.getRequestMethod() + " " + query);
            int status = 200;
            String answer;
            if (exchange.getRequestMethod().equals("GET")) {
                // sysparm_query=correlation_id=DRAFT-xxxx
                List<String> found = new ArrayList<>();
                for (int i = 0; i < incidents.size(); i++) {
                    if (query.contains("correlation_id=" + incidents.get(i).get("correlation_id").asString())) {
                        found.add("{\"number\":\"INC00" + (10001 + i) + "\"}");
                    }
                }
                answer = "{\"result\":[" + String.join(",", found) + "]}";
            } else if (createStatus != 201) {
                status = createStatus;
                answer = "{\"error\":{\"message\":\"refused\"}}";
            } else {
                incidents.add(json.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                status = 201;
                answer = "{\"result\":{\"number\":\"INC00" + (10000 + incidents.size()) + "\",\"sys_id\":\"abc\"}}";
            }
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void stop() {
        server.stop(0);
    }
}
