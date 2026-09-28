package org.webfunction;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class ClientTest {

    private static String readBody(com.sun.net.httpserver.HttpExchange ex) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        ex.getRequestBody().transferTo(buf);
        return buf.toString(StandardCharsets.UTF_8);
    }

    private static Map<String, Object> decodeBody(com.sun.net.httpserver.HttpExchange ex) throws IOException {
        String body = readBody(ex);
        if (body.isEmpty()) return Map.of();
        return (Map<String, Object>) Json.MAPPER.readValue(body, Object.class);
    }

    private static void writeJson(com.sun.net.httpserver.HttpExchange ex, int status, Object value) throws IOException {
        byte[] bytes = Json.MAPPER.writeValueAsBytes(value);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Test
    void fromPackageEndpointAndCall() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String[] baseUrlHolder = new String[1];

        server.createContext("/package", ex -> {
            Map<String, Object> pkg = Map.of(
                    "base_url", baseUrlHolder[0],
                    "endpoints", List.of(
                            Map.of(
                                    "name", "find-user",
                                    "returns", "object",
                                    "arguments", List.of(Map.of("name", "id", "type", "string", "flags", List.of("required")))
                            ),
                            Map.of(
                                    "name", "list-people",
                                    "returns", List.of(List.of("object")),
                                    "flags", List.of("paginated"),
                                    "arguments", List.of()
                            )
                    )
            );
            writeJson(ex, 200, pkg);
        });

        server.createContext("/find-user", ex -> {
            Map<String, Object> args = decodeBody(ex);
            String id = (String) args.get("id");
            if ("missing".equals(id)) {
                writeJson(ex, 400, List.of("USER_NOT_FOUND", "No user with that id.", Map.of("id", id)));
                return;
            }
            writeJson(ex, 200, Map.of("id", id, "name", "Ada"));
        });

        server.createContext("/list-people", ex -> {
            Map<String, Object> args = decodeBody(ex);
            if ("page2".equals(args.get("cursor"))) {
                Map<String, Object> body = new java.util.LinkedHashMap<>();
                body.put("page", List.of(Map.of("person_id", "p2")));
                body.put("next", null);
                body.put("previous", Map.of("cursor", "page1"));
                writeJson(ex, 200, body);
                return;
            }
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("page", List.of(Map.of("person_id", "p1")));
            body.put("next", Map.of("cursor", "page2"));
            body.put("previous", null);
            writeJson(ex, 200, body);
        });

        server.start();
        try {
            baseUrlHolder[0] = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            String packageUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/package";

            Client client = Client.fromPackageEndpoint(packageUrl, new Options());
            assertEquals(2, client.getPackage().endpoints().size());

            // plain call
            Object result = client.call("find-user", Map.of("id", "123"));
            assertInstanceOf(Map.class, result);
            assertEquals("Ada", ((Map<?, ?>) result).get("name"));

            // underscore name maps to hyphenated endpoint
            Object result2 = client.call("find_user", Map.of("id", "123"));
            assertEquals("123", ((Map<?, ?>) result2).get("id"));

            // bad request error triple
            BadRequestException ex = assertThrows(BadRequestException.class,
                    () -> client.call("find-user", Map.of("id", "missing")));
            assertEquals("USER_NOT_FOUND", ex.getCode());

            // pagination via paginated flag
            Object pageResult = client.call("list-people", Map.of());
            assertInstanceOf(Page.class, pageResult);
            Page page = (Page) pageResult;
            assertEquals(1, page.getItems().size());
            assertTrue(page.hasNext());
            assertFalse(page.hasPrevious());

            Optional<Page> next = page.nextPage();
            assertTrue(next.isPresent());
            assertFalse(next.get().hasNext());
            assertTrue(next.get().hasPrevious());

            Optional<Page> prev = next.get().previousPage();
            assertTrue(prev.isPresent());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void pipelining() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String[] baseUrlHolder = new String[1];

        server.createContext("/package", ex -> {
            Map<String, Object> pkg = Map.of(
                    "base_url", baseUrlHolder[0],
                    "pipeline_url", baseUrlHolder[0] + "run-pipeline",
                    "endpoints", List.of(
                            Map.of("name", "find-user", "returns", "object",
                                    "arguments", List.of(Map.of("name", "id", "type", "string", "flags", List.of("required")))),
                            Map.of("name", "create-order", "returns", "object",
                                    "arguments", List.of(Map.of("name", "user_id", "type", "string", "flags", List.of("required"))))
                    )
            );
            writeJson(ex, 200, pkg);
        });

        Pattern refPattern = Pattern.compile("^\\$\\[(\\d+)\\]\\.(\\w+)$");

        server.createContext("/run-pipeline", ex -> {
            Map<String, Object> body = decodeBody(ex);
            List<Map<String, Object>> steps = (List<Map<String, Object>>) (List<?>) body.get("steps");

            List<Object> results = new java.util.ArrayList<>();
            for (Map<String, Object> step : steps) {
                String url = (String) step.get("url");
                Map<String, Object> stepBody = new java.util.LinkedHashMap<>((Map<String, Object>) step.get("body"));

                for (var entry : stepBody.entrySet()) {
                    if (entry.getValue() instanceof String s) {
                        Matcher m = refPattern.matcher(s);
                        if (m.matches()) {
                            int idx = Integer.parseInt(m.group(1));
                            if (idx < results.size() && results.get(idx) instanceof Map<?, ?> obj) {
                                entry.setValue(obj.get(m.group(2)));
                            }
                        }
                    }
                }

                if (url.endsWith("/find-user")) {
                    results.add(Map.of("id", "123", "name", "Ada"));
                } else if (url.endsWith("/create-order")) {
                    results.add(Map.of("id", "order-1", "user_id", stepBody.get("user_id")));
                } else {
                    results.add(null);
                }
            }
            writeJson(ex, 200, results);
        });

        server.start();
        try {
            baseUrlHolder[0] = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            String packageUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/package";

            Client client = Client.fromPackageEndpoint(packageUrl, new Options().pipelined(true));

            Object userResult = client.call("find-user", Map.of("id", "123"));
            assertInstanceOf(Promise.class, userResult);
            Promise userPromise = (Promise) userResult;

            Object orderResult = client.call("create-order", Map.of("user_id", userPromise.field("id")));
            assertInstanceOf(Promise.class, orderResult);
            Promise orderPromise = (Promise) orderResult;

            Object resolved = orderPromise.resolve();
            assertInstanceOf(Map.class, resolved);
            assertEquals("123", ((Map<?, ?>) resolved).get("user_id"));

            assertTrue(userPromise.isResolved());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unresolvedPromiseThrows() {
        Pipeline pipeline = new Pipeline("https://example.com/run-pipeline");
        Promise promise = pipeline.addStep("https://example.com/find-user", Map.of(), Map.of("id", "1"));
        assertThrows(UnresolvedPromiseException.class, promise::value);
    }

    @Test
    void typeValidation() {
        Type emailType = new Type(List.of(new TypeAlt("string", "email", null)));
        assertTrue(emailType.valid("ada@example.com"));
        assertFalse(emailType.valid("not-an-email"));

        Type u32Type = new Type(List.of(new TypeAlt("number", "u32", null)));
        assertTrue(u32Type.valid(42));
        assertFalse(u32Type.valid(-1));

        Type arrType = new Type(List.of(new TypeAlt("array", null, new Type(List.of(new TypeAlt("string", null, null))))));
        assertTrue(arrType.valid(List.of("a", "b")));
        assertFalse(arrType.valid(List.of("a", 1)));
    }

    @Test
    void fromUrlFetch() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/package.json", ex -> {
            assertEquals("GET", ex.getRequestMethod());
            String query = ex.getRequestURI().getQuery();
            assertEquals("api_version=2", query);
            Map<String, Object> pkg = Map.of(
                    "base_url", "https://example.com/",
                    "endpoints", List.of(Map.of("name", "ping", "returns", "boolean", "arguments", List.of()))
            );
            writeJson(ex, 200, pkg);
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/package.json";
            Client client = Client.fromUrl(url, new Options().version("2"));
            assertEquals(1, client.getPackage().endpoints().size());
            assertEquals("https://example.com/", client.getPackage().baseUrl());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void objectInContext() {
        Package pkg = new Package(
                null, null, null, null, null, null, null,
                List.of(),
                List.of(),
                List.of(
                        new ObjectSchema("user",
                                List.of(new Argument("id", new Type(List.of(new TypeAlt("string", null, null))), null, null, null, null)),
                                List.of(
                                        new Attribute("id", new Type(List.of(new TypeAlt("string", null, null))), null, null, null),
                                        new Attribute("name", new Type(List.of(new TypeAlt("string", null, null))), null, null, null)
                                )
                        ),
                        new ObjectSchema("argument-only",
                                List.of(new Argument("x", new Type(List.of(new TypeAlt("string", null, null))), null, null, null, null)),
                                null
                        )
                )
        );

        assertTrue(pkg.objectInContext("user", Package.ObjectContext.ARGUMENT).isPresent());
        Optional<ObjectSchema> userAttrs = pkg.objectInContext("user", Package.ObjectContext.ATTRIBUTE);
        assertTrue(userAttrs.isPresent());
        assertEquals(2, userAttrs.get().attributes().size());

        assertTrue(pkg.objectInContext("argument-only", Package.ObjectContext.ATTRIBUTE).isEmpty());
        assertTrue(pkg.objectInContext("does-not-exist", Package.ObjectContext.ARGUMENT).isEmpty());
    }

    @Test
    void gzipResponseIsDecompressed() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ping", ex -> {
            byte[] payload = Json.MAPPER.writeValueAsBytes(Map.of("ok", true));
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            try (var gz = new java.util.zip.GZIPOutputStream(buf)) {
                gz.write(payload);
            }
            byte[] gzipped = buf.toByteArray();
            ex.getResponseHeaders().add("Content-Encoding", "gzip");
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, gzipped.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(gzipped);
            }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/ping";
            Object result = RequestExecutor.execute(url, RequestExecutor.Options.none());
            assertInstanceOf(Map.class, result);
            assertEquals(true, ((Map<?, ?>) result).get("ok"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void joinUrl() {
        // Regression test for a real bug: Client.call used URI.resolve (RFC 3986 relative
        // reference resolution), which replaces the last path segment of a base_url that
        // doesn't end in "/" instead of appending under it - so "https://api.example.com/v1"
        // silently lost its "v1". The spec (https://webfunction.org/package#url-composition)
        // calls for plain string normalization instead: append the name directly if base_url
        // ends in "/", otherwise insert a single "/".
        String[][] cases = {
                {"https://api.example.com", "list-people", "https://api.example.com/list-people"},
                {"https://api.example.com/", "list-people", "https://api.example.com/list-people"},
                {"https://api.example.com/v1", "list-people", "https://api.example.com/v1/list-people"},
                {"https://api.example.com/v1/", "list-people", "https://api.example.com/v1/list-people"},
                {"https://api.example.com/v1/merchants", "list-people", "https://api.example.com/v1/merchants/list-people"},
        };
        for (String[] c : cases) {
            assertEquals(c[2], Client.joinUrl(c[0], c[1]), "joinUrl(" + c[0] + ", " + c[1] + ")");
        }
    }

    @Test
    void callPreservesBaseUrlPath() throws IOException {
        // End-to-end version of joinUrl(): a real server, and base_urls with a path
        // segment (with and without a trailing slash). The other tests here all use a
        // bare-host base_url with a trailing slash, which is why the bug went unnoticed.
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String[] baseUrlHolder = new String[1];
        List<String> requestedPaths = new java.util.concurrent.CopyOnWriteArrayList<>();

        server.createContext("/package", ex -> writeJson(ex, 200, Map.of(
                "base_url", baseUrlHolder[0],
                "endpoints", List.of(Map.of("name", "ping", "returns", "boolean", "arguments", List.of())))));
        server.createContext("/", ex -> {
            requestedPaths.add(ex.getRequestURI().getPath());
            writeJson(ex, 200, true);
        });
        server.start();
        try {
            String origin = "http://127.0.0.1:" + server.getAddress().getPort();
            for (String basePath : List.of("/v1", "/v1/", "/v1/merchants")) {
                baseUrlHolder[0] = origin + basePath;
                Client.fromPackageEndpoint(origin + "/package", new Options()).call("ping", Map.of());
            }
            assertEquals(List.of("/v1/ping", "/v1/ping", "/v1/merchants/ping"), requestedPaths);
        } finally {
            server.stop(0);
        }
    }
}