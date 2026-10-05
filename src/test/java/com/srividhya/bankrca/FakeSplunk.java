package com.srividhya.bankrca;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

/** A stand-in for Splunk's REST API on localhost: records what it was sent and answers with canned JSON. */
public class FakeSplunk {

    private final HttpServer server;
    public volatile String path;
    public volatile String authorization;
    public volatile Map<String, String> form;
    public volatile int status = 200;
    public volatile String response = "{\"results\":[]}";
    /** Logins: how many were attempted, what the last one sent, and how to answer them. */
    public final AtomicInteger logins = new AtomicInteger();
    public final AtomicInteger searches = new AtomicInteger();
    public volatile Map<String, String> loginForm;
    public volatile int loginStatus = 200;
    /** Session keys handed out are session-1, session-2, ...; a search with any other key gets 401. */
    public volatile boolean expireSessions;

    public FakeSplunk() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", exchange -> {
            Map<String, String> sent = new HashMap<>();
            for (String pair : new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).split("&")) {
                String[] kv = pair.split("=", 2);
                sent.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
            int answerStatus = status;
            String answer = response;
            if (exchange.getRequestURI().getPath().equals("/services/auth/login")) {
                loginForm = sent;
                answerStatus = loginStatus;
                answer = "{\"sessionKey\":\"session-" + logins.incrementAndGet() + "\"}";
            } else {
                path = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
                authorization = exchange.getRequestHeaders().getFirst("Authorization");
                form = sent;
                if (expireSessions && authorization != null && authorization.startsWith("Splunk ")
                        && !authorization.equals("Splunk session-" + logins.get())) {
                    answerStatus = 401;
                }
                searches.incrementAndGet();
            }
            byte[] body = answer.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(answerStatus, body.length);
            exchange.getResponseBody().write(body);
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
