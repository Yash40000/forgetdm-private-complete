package io.forgetdm.mainframe.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.common.ApiException;
import io.forgetdm.config.ForgeProps;
import io.forgetdm.mainframe.MainframeConnectionEntity;
import io.forgetdm.mainframe.MainframeCredentialResolver;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ZOWE / z-OSMF provider — real mainframe access over the z/OSMF data set REST interface
 * ({@code /zosmf/restfiles/ds}). Only works against a live z/OSMF; the API shape is per IBM's docs.
 *
 *   list   GET  {base}/restfiles/ds?dslevel=PATTERN
 *   fetch  GET  {base}/restfiles/ds/{dsname}        (X-IBM-Data-Type: binary)
 *   put    PUT  {base}/restfiles/ds/{dsname}        (X-IBM-Data-Type: binary)
 *
 * Target datasets must already be allocated on the mainframe with the intended DCB (RECFM/LRECL);
 * z/OSMF writes content, not allocation attributes.
 */
@Component
public class ZoweTransport implements MainframeTransport {

    private final ObjectMapper json = new ObjectMapper();
    private final MainframeCredentialResolver credentials;
    private final ForgeProps properties;

    public ZoweTransport(MainframeCredentialResolver credentials, ForgeProps properties) {
        this.credentials = credentials;
        this.properties = properties;
    }

    private String baseUrl(MainframeConnectionEntity c) {
        if (c.getHost() == null || c.getHost().isBlank()) throw ApiException.bad("ZOWE connection needs a host");
        int port = c.getPort() == null ? 443 : c.getPort();
        String path = (c.getBasePath() == null || c.getBasePath().isBlank()) ? "/zosmf" : c.getBasePath();
        if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return "https://" + c.getHost() + ":" + port + path;
    }

    private HttpClient client(MainframeConnectionEntity c) {
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(20));
        if (c.isTrustAllCerts()) {
            if (!properties.getMainframe().isAllowInsecureTls()) {
                throw ApiException.bad("Connection '" + c.getName()
                        + "' requests trust-all TLS, but forgetdm.mainframe.allow-insecure-tls is disabled");
            }
            try {
                SSLContext sc = SSLContext.getInstance("TLS");
                sc.init(null, new TrustManager[]{ trustAll() }, new SecureRandom());
                b.sslContext(sc);
                SSLParameters ssl = new SSLParameters();
                ssl.setEndpointIdentificationAlgorithm("");
                b.sslParameters(ssl);
            } catch (Exception e) {
                throw ApiException.bad("Could not build trust-all SSL context: " + e.getMessage());
            }
        }
        return b.build();
    }

    private static X509TrustManager trustAll() {
        return new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] x, String a) { }
            public void checkServerTrusted(X509Certificate[] x, String a) { }
        };
    }

    private HttpRequest.Builder req(MainframeConnectionEntity c, String url) {
        if (c.getAuthType() != null && !c.getAuthType().isBlank()
                && !"BASIC".equalsIgnoreCase(c.getAuthType())) {
            throw ApiException.bad("Unsupported z/OSMF authType '" + c.getAuthType() + "'");
        }
        String auth = Base64.getEncoder().encodeToString(
                ((c.getUsername() == null ? "" : c.getUsername()) + ":" +
                 credentials.password(c)).getBytes(StandardCharsets.UTF_8));
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(java.time.Duration.ofMinutes(30))
                .header("Authorization", "Basic " + auth)
                .header("X-CSRF-ZOSMF-HEADER", "true");
    }

    @Override
    public List<RemoteFile> list(MainframeConnectionEntity c, String pattern) {
        Matcher memberPattern = Pattern.compile("^(.+)\\(([^)]+)\\)$").matcher(
                pattern == null ? "" : pattern.trim());
        if (memberPattern.matches() && !memberPattern.group(2).matches("[+-]?\\d+")) {
            return listMembers(c, memberPattern.group(1), memberPattern.group(2));
        }
        if (pattern != null && pattern.trim().matches(".*\\([+-]?\\d+\\)$")) {
            return List.of(new RemoteFile(pattern.trim(), null, null, null, "GDG"));
        }
        String dslevel = pattern == null || pattern.isBlank() ? "**" : pattern;
        String url = baseUrl(c) + "/restfiles/ds?dslevel=" + URLEncoder.encode(dslevel, StandardCharsets.UTF_8);
        HttpResponse<String> r = send(c, req(c, url)
                .header("X-IBM-Attributes", "base")
                .header("X-IBM-Max-Items", "0").GET().build(), HttpResponse.BodyHandlers.ofString());
        ensure2xx(r.statusCode(), r.body(), "list");
        List<RemoteFile> out = new ArrayList<>();
        try {
            JsonNode items = json.readTree(r.body()).path("items");
            for (JsonNode it : items) {
                out.add(new RemoteFile(
                        it.path("dsname").asText(null),
                        it.path("recfm").asText(null),
                        it.hasNonNull("lrecl") ? it.path("lrecl").asInt() : null,
                        it.hasNonNull("sizex") ? it.path("sizex").asLong() : null,
                        it.path("dsorg").asText(null)));
            }
        } catch (Exception e) {
            throw ApiException.bad("Could not parse z/OSMF list response: " + e.getMessage());
        }
        return out;
    }

    private List<RemoteFile> listMembers(MainframeConnectionEntity c, String dsn, String pattern) {
        RemoteFile parent = list(c, dsn).stream()
                .filter(item -> dsn.equalsIgnoreCase(item.name())).findFirst()
                .orElseThrow(() -> ApiException.bad("Partitioned data set not found: " + dsn));
        String url = baseUrl(c) + "/restfiles/ds/" + dsn + "/member?pattern="
                + URLEncoder.encode(pattern, StandardCharsets.UTF_8);
        HttpResponse<String> response = send(c, req(c, url)
                        .header("X-IBM-Attributes", "base")
                        .header("X-IBM-Max-Items", "0").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        ensure2xx(response.statusCode(), response.body(), "list members " + dsn);
        List<RemoteFile> out = new ArrayList<>();
        try {
            for (JsonNode item : json.readTree(response.body()).path("items")) {
                String member = item.path("member").asText(null);
                if (member != null) out.add(new RemoteFile(dsn + "(" + member + ")",
                        parent.recfm(), parent.lrecl(), null, "PDS_MEMBER"));
            }
        } catch (Exception e) {
            throw ApiException.bad("Could not parse z/OSMF member list response: " + e.getMessage());
        }
        return out;
    }

    @Override
    public RemoteFile stat(MainframeConnectionEntity c, String name) {
        for (RemoteFile f : list(c, name)) {
            if (f.name() != null && f.name().equalsIgnoreCase(name)) return f;
        }
        List<RemoteFile> any = list(c, name);
        if (!any.isEmpty()) return any.get(0);
        throw ApiException.bad("Dataset not found: " + name);
    }

    @Override
    public ReadHandle openRead(MainframeConnectionEntity c, String name) {
        String url = baseUrl(c) + "/restfiles/ds/" + name;
        HttpResponse<InputStream> r = send(c, req(c, url).header("X-IBM-Data-Type", "binary").GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        ensure2xx(r.statusCode(), "<binary>", "fetch " + name);
        String etag = requiredEtag(r, name);
        Long length = r.headers().firstValueAsLong("Content-Length").isPresent()
                ? r.headers().firstValueAsLong("Content-Length").getAsLong() : null;
        return new ReadHandle(r.body(), new ResourceVersion(true, etag), length);
    }

    @Override
    public ResourceVersion version(MainframeConnectionEntity c, String name) {
        String url = baseUrl(c) + "/restfiles/ds/" + name;
        HttpResponse<Void> response = send(c, req(c, url)
                        .header("X-IBM-Data-Type", "binary")
                        .header("X-IBM-Max-Items", "0").GET().build(),
                HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() == 404) return ResourceVersion.missing();
        ensure2xx(response.statusCode(), "", "read version " + name);
        return new ResourceVersion(true, requiredEtag(response, name));
    }

    @Override
    public PublishReceipt publish(MainframeConnectionEntity c, String name, Path stagedData,
                                  String recfm, Integer lrecl, ResourceVersion expectedTarget) {
        if (name != null && name.matches(".*\\([+-]?\\d+\\)$")) {
            throw ApiException.bad("GDG relative targets require the generation allocation adapter: " + name);
        }
        DatasetRef target = DatasetRef.parse(name);
        DatasetRef stage = target.member() == null
                ? new DatasetRef(stagingDatasetName(target.dsn()), null)
                : new DatasetRef(target.dsn(), stagingMemberName());
        boolean stageExists = false;
        try {
            assertVersion(c, name, expectedTarget);
            if (stage.member() == null) {
                createStageDataset(c, stage.dsn(), target.dsn(), expectedTarget != null && expectedTarget.exists(),
                        recfm, lrecl, stagedData);
                stageExists = true;
            }
            putBinary(c, stage.render(), stagedData, null);
            stageExists = true;
            assertVersion(c, name, expectedTarget);

            if (target.member() == null && (expectedTarget == null || !expectedTarget.exists())) {
                utility(c, target.render(), Map.of(
                        "request", "rename",
                        "from-dataset", Map.of("dsn", stage.dsn())), null);
                stageExists = false;
            } else {
                Map<String, Object> from = new LinkedHashMap<>();
                from.put("dsn", stage.dsn());
                if (stage.member() != null) from.put("member", stage.member());
                Map<String, Object> request = new LinkedHashMap<>();
                request.put("request", "copy");
                request.put("from-dataset", from);
                request.put("replace", true);
                utility(c, target.render(), request,
                        expectedTarget != null && expectedTarget.exists() ? expectedTarget.value() : null);
            }
            ResourceVersion published = version(c, name);
            return new PublishReceipt(name, published, stage.render());
        } catch (java.io.IOException e) {
            throw ApiException.bad("Could not inspect staged mainframe image: " + e.getMessage());
        } finally {
            if (stageExists) deleteQuietly(c, stage.render());
        }
    }

    private void createStageDataset(MainframeConnectionEntity c, String stageName, String targetName,
                                    boolean targetExists, String recfm, Integer lrecl, Path data)
            throws java.io.IOException {
        Map<String, Object> allocation = new LinkedHashMap<>();
        if (targetExists) {
            allocation.put("like", targetName);
        } else {
            allocation.put("dsorg", "PS");
            allocation.put("alcunit", "TRK");
            long tracks = Math.max(1, (Files.size(data) + 56_663L) / 56_664L);
            allocation.put("primary", Math.min(Integer.MAX_VALUE, tracks));
            allocation.put("secondary", Math.max(1, Math.min(Integer.MAX_VALUE, tracks / 4)));
            allocation.put("recfm", recfm == null || recfm.isBlank() ? "FB" : recfm);
            if (lrecl != null) allocation.put("lrecl", lrecl);
        }
        String body = writeJson(allocation);
        String url = baseUrl(c) + "/restfiles/ds/" + stageName;
        HttpResponse<String> response = send(c, req(c, url)
                        .header("Content-Type", "application/json; charset=UTF-8")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        ensure2xx(response.statusCode(), response.body(), "allocate staging data set " + stageName);
    }

    private void putBinary(MainframeConnectionEntity c, String name, Path data, String ifMatch)
            throws java.io.IOException {
        String url = baseUrl(c) + "/restfiles/ds/" + name;
        HttpRequest.Builder builder = req(c, url)
                .header("X-IBM-Data-Type", "binary")
                .header("Content-Type", "application/octet-stream");
        if (ifMatch != null) builder.header("If-Match", ifMatch);
        HttpResponse<String> response = send(c, builder.PUT(HttpRequest.BodyPublishers.ofFile(data)).build(),
                HttpResponse.BodyHandlers.ofString());
        ensure2xx(response.statusCode(), response.body(), "put " + name);
    }

    private void utility(MainframeConnectionEntity c, String target, Map<String, Object> request, String ifMatch) {
        String url = baseUrl(c) + "/restfiles/ds/" + target;
        HttpRequest.Builder builder = req(c, url).header("Content-Type", "application/json; charset=UTF-8");
        if (ifMatch != null) builder.header("If-Match", ifMatch);
        HttpResponse<String> response = send(c,
                builder.PUT(HttpRequest.BodyPublishers.ofString(writeJson(request))).build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 412) {
            throw ApiException.conflict("Target data set changed before atomic publish: " + target);
        }
        ensure2xx(response.statusCode(), response.body(), request.get("request") + " " + target);
    }

    private void deleteQuietly(MainframeConnectionEntity c, String name) {
        try {
            String url = baseUrl(c) + "/restfiles/ds/" + name;
            HttpResponse<String> response = send(c, req(c, url).DELETE().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 404) ensure2xx(response.statusCode(), response.body(), "delete staging " + name);
        } catch (Exception ignored) {
            // The failed job retains the staging name in its evidence; operators can clean up if host deletion failed.
        }
    }

    private String writeJson(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw ApiException.bad("Could not create z/OSMF request: " + e.getMessage()); }
    }

    private static String requiredEtag(HttpResponse<?> response, String name) {
        return response.headers().firstValue("ETag")
                .orElseThrow(() -> ApiException.bad("z/OSMF did not return an ETag for " + name));
    }

    private static String stagingDatasetName(String target) {
        String hlq = target == null ? "" : target.split("\\.")[0].toUpperCase();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 5).toUpperCase();
        String candidate = hlq + ".FDM" + suffix;
        if (candidate.length() > 44) throw ApiException.bad("Cannot derive a valid staging data set name for " + target);
        return candidate;
    }

    private static String stagingMemberName() {
        return "FDM" + UUID.randomUUID().toString().replace("-", "").substring(0, 5).toUpperCase();
    }

    private record DatasetRef(String dsn, String member) {
        private static final Pattern MEMBER = Pattern.compile("^(.+)\\(([A-Za-z#$@][A-Za-z0-9#$@]{0,7})\\)$");
        static DatasetRef parse(String value) {
            if (value == null || value.isBlank()) throw ApiException.bad("Target data set name is required");
            Matcher match = MEMBER.matcher(value.trim());
            return match.matches() ? new DatasetRef(match.group(1), match.group(2))
                    : new DatasetRef(value.trim(), null);
        }
        String render() { return member == null ? dsn : dsn + "(" + member + ")"; }
    }

    private <T> HttpResponse<T> send(MainframeConnectionEntity c, HttpRequest req, HttpResponse.BodyHandler<T> h) {
        try {
            return client(c).send(req, h);
        } catch (Exception e) {
            throw ApiException.bad("z/OSMF request failed: " + e.getMessage());
        }
    }

    private static void ensure2xx(int status, String body, String op) {
        if (status / 100 != 2) throw ApiException.bad("z/OSMF " + op + " returned HTTP " + status + ": " + body);
    }
}
