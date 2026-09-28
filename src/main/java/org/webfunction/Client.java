package org.webfunction;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Objects;

/**
 * Wraps a {@link Package} and provides a way to invoke its endpoints.
 *
 * <p>Unlike the Ruby/JS/PHP reference clients, there is no dynamic
 * dispatch here (Java has no clean {@code method_missing}/{@code Proxy}/
 * {@code __call} equivalent for this - {@link java.lang.reflect.Proxy}
 * only works over interfaces and would add real complexity for
 * questionable benefit). Every call goes through the explicit {@link
 * #call}. Generated named methods (e.g. a future {@code listContacts
 * (args)} wrapping {@code call("list-contacts", args)}) are entirely a
 * future wfn CLI codegen target's responsibility, not this library's -
 * same decision already made for webfunction-go.
 */
public final class Client {

    private final Package pkg;
    private final String baseUrl;
    private String bearerAuth;
    private String version;
    private Pipeline pipeline;
    private final HttpClient httpClient;

    private Client(Package pkg, String baseUrl, String bearerAuth, String version, Pipeline pipeline, HttpClient httpClient) {
        this.pkg = pkg;
        this.baseUrl = baseUrl;
        this.bearerAuth = bearerAuth;
        this.version = version;
        this.pipeline = pipeline;
        this.httpClient = httpClient;
    }

    /**
     * Fetches the package by invoking {@code endpointUrl} as a Web
     * Function endpoint (an HTTP POST, per the package retrieval spec)
     * and builds a Client from it.
     */
    public static Client fromPackageEndpoint(String endpointUrl, Options opts) {
        Object result = RequestExecutor.execute(endpointUrl, new RequestExecutor.Options(
                opts.bearerAuthValue(), opts.versionValue(), Map.of(), opts.httpClientValue()
        ));
        Package pkg = Json.MAPPER.convertValue(result, Package.class);
        return fromPackage(pkg, opts);
    }

    /**
     * Fetches the package as plain JSON via an HTTP GET, rather than
     * invoking it as a Web Function endpoint. Use this when the package
     * document is served as a static JSON file instead of a POST-able
     * endpoint. When {@code opts}' version is set, it's sent as an
     * "api_version" query parameter rather than an Api-Version header,
     * matching how a plain GET has no room for the usual
     * endpoint-invocation headers.
     */
    public static Client fromUrl(String rawUrl, Options opts) {
        URI uri;
        try {
            uri = new URI(rawUrl);
            String version = opts.versionValue();
            if (version != null && !version.isEmpty()) {
                String query = (uri.getQuery() == null ? "" : uri.getQuery() + "&") + "api_version=" + version;
                uri = new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), query, uri.getFragment());
            }
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("parsing url " + rawUrl, e);
        }

        HttpClient client = opts.httpClientValue() != null ? opts.httpClientValue() : HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder(uri)
                .GET()
                .header("Accept", "application/json")
                .header("User-Agent", "webfunction-java/" + RequestExecutor.MODULE_VERSION)
                .header("Accept-Encoding", "gzip")
                .build();

        HttpResponse<byte[]> resp;
        try {
            resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException("fetching package from " + rawUrl, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while fetching package from " + rawUrl, e);
        }

        byte[] body = RequestExecutor.readResponseBody(resp);
        if (resp.statusCode() != 200) {
            throw new UnexpectedStatusCodeException(resp.statusCode(), new String(body, java.nio.charset.StandardCharsets.UTF_8));
        }

        Package pkg;
        try {
            pkg = Json.MAPPER.readValue(body, Package.class);
        } catch (IOException e) {
            throw new JsonParseException(e.getMessage(), resp.statusCode(), new String(body, java.nio.charset.StandardCharsets.UTF_8));
        }

        return fromPackage(pkg, opts);
    }

    /** Builds a Client directly from an already-loaded {@link Package}, making no request of its own. */
    public static Client fromPackage(Package pkg, Options opts) {
        Pipeline pipeline = null;
        if (opts.pipelinedValue() && pkg.pipelineUrl() != null && !pkg.pipelineUrl().isEmpty()) {
            pipeline = new Pipeline(pkg.pipelineUrl(), opts.httpClientValue());
        }
        return new Client(pkg, pkg.baseUrl(), opts.bearerAuthValue(), opts.versionValue(), pipeline, opts.httpClientValue());
    }

    /** The {@link Package} this client wraps. */
    public Package getPackage() {
        return pkg;
    }

    /** Updates the bearer token used on subsequent calls. */
    public void setBearerAuth(String bearerAuth) {
        this.bearerAuth = bearerAuth;
    }

    /** Updates the API version used on subsequent calls. */
    public void setVersion(String version) {
        this.version = version;
    }

    /** Replaces the client's pipeline. Pass {@code null} to make calls execute immediately again instead of batching. */
    public void setPipeline(Pipeline pipeline) {
        this.pipeline = pipeline;
    }

    /**
     * Invokes the named endpoint with the given arguments.
     *
     * <p>If the client is unpipelined (the common case), the return
     * value is the decoded response - a {@code Map<String, Object>},
     * {@code List<Object>}, {@code String}, a {@link Number} subtype,
     * {@link Boolean}, {@code null}, or a {@link Page} for an endpoint
     * flagged "paginated" (see {@link Endpoint#paginated()}).
     *
     * <p>If the client is pipelined (see {@link Options#pipelined} /
     * {@link #setPipeline}), the call is queued as a pipeline step
     * instead of executed immediately, and the return value is a {@link
     * Promise} standing in for the eventual result - callers that use
     * pipelining need to cast the returned {@code Object} to {@link
     * Promise}. This mirrors the Ruby reference's polymorphic return (a
     * value normally, a Promise when pipelined) as closely as Java's
     * static typing allows.
     */
    public Object call(String name, Map<String, Object> args) {
        String dashed = name.replace('_', '-');
        String endpointUrl = joinUrl(baseUrl, dashed);

        Endpoint endpoint = pkg.endpoint(dashed).orElse(null);

        if (pipeline != null) {
            RequestExecutor.Options headerOpts = new RequestExecutor.Options(bearerAuth, version, Map.of(), httpClient);
            return pipeline.addStep(endpointUrl, headerOpts.headerMap(), args);
        }

        Object result = RequestExecutor.execute(endpointUrl, new RequestExecutor.Options(bearerAuth, version, args, httpClient));

        if (endpoint != null && endpoint.paginated()) {
            return Page.wrap(result, endpointUrl, bearerAuth, version, httpClient);
        }

        return result;
    }

    /**
     * Joins a package's base URL with an endpoint name, per
     * https://webfunction.org/package#url-composition: if {@code baseUrl}
     * ends in "/", the name is appended directly; otherwise a single "/"
     * is inserted. This is plain string-level normalization, NOT RFC 3986
     * relative reference resolution.
     *
     * <p>An earlier version used {@code URI.resolve}, which was a real
     * bug: RFC 3986 resolution treats the last path segment of a base URL
     * that doesn't end in "/" as replaceable, so resolving "list-people"
     * against "https://api.example.com/v1" silently dropped "v1" and
     * produced "https://api.example.com/list-people" instead of
     * "https://api.example.com/v1/list-people". The spec's rule has no
     * such failure mode, so a base_url works with or without a trailing
     * slash.
     */
    static String joinUrl(String baseUrl, String endpointName) {
        // Fail loudly on a package with no base_url, as URI.create(null) did,
        // rather than silently producing "null/<name>".
        Objects.requireNonNull(baseUrl, "package has no base_url");
        return baseUrl.endsWith("/") ? baseUrl + endpointName : baseUrl + "/" + endpointName;
    }
}