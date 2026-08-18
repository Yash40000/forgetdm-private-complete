package io.forgetdm.provision.loader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.common.ApiException;
import io.forgetdm.config.ForgeProps;
import io.forgetdm.mainframe.MainframeConnectionEntity;
import io.forgetdm.mainframe.MainframeCredentialResolver;
import org.springframework.stereotype.Component;

import javax.net.ssl.*;
import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.*;

@Component
public class ZosmfJobClient {
    private final ObjectMapper json = new ObjectMapper();
    private final MainframeCredentialResolver credentials;
    private final ForgeProps properties;

    public ZosmfJobClient(MainframeCredentialResolver credentials, ForgeProps properties) {
        this.credentials = credentials;
        this.properties = properties;
    }

    public Map<String, Object> readiness(MainframeConnectionEntity connection) {
        String owner = connection.getUsername() == null ? "*" : connection.getUsername();
        HttpResponse<String> response = send(connection, request(connection,
                baseUrl(connection) + "/restjobs/jobs?owner=" + encode(owner))
                .GET().build());
        ensure2xx(response, "query JES jobs");
        return Map.of("ready", true, "httpStatus", response.statusCode(), "connection", connection.getName());
    }

    public void allocateRecordDataset(MainframeConnectionEntity connection, String dataset, long bytes, int lrecl) {
        Map<String, Object> allocation = new LinkedHashMap<>();
        allocation.put("dsorg", "PS");
        allocation.put("alcunit", "TRK");
        long tracks = Math.max(1, (bytes + 56_663L) / 56_664L);
        allocation.put("primary", Math.min(16_777_215L, tracks));
        allocation.put("secondary", Math.max(1, Math.min(16_777_215L, tracks / 4)));
        allocation.put("recfm", "VB");
        allocation.put("lrecl", lrecl);
        allocation.put("blksize", 0);
        HttpResponse<String> response = send(connection, request(connection, datasetUrl(connection, dataset))
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(allocation))).build());
        ensure2xx(response, "allocate " + dataset);
    }

    public void uploadUtf8Records(MainframeConnectionEntity connection, String dataset, Path file, int maxRecordBytes) {
        HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.ofInputStream(
                () -> new RecordFramingInputStream(file, maxRecordBytes));
        HttpResponse<String> response = send(connection, request(connection, datasetUrl(connection, dataset))
                .header("X-IBM-Data-Type", "record")
                .header("Content-Type", "application/octet-stream")
                .PUT(body).build());
        ensure2xx(response, "upload records to " + dataset);
    }

    public Job submit(MainframeConnectionEntity connection, String jcl) {
        HttpResponse<String> response = send(connection, request(connection, baseUrl(connection) + "/restjobs/jobs")
                .header("Content-Type", "text/plain; charset=UTF-8")
                .header("Accept", "application/json")
                .header("X-IBM-Intrdr-Mode", "TEXT")
                .PUT(HttpRequest.BodyPublishers.ofString(jcl, StandardCharsets.UTF_8)).build());
        ensure2xx(response, "submit LOAD job");
        return job(readJson(response.body()));
    }

    public Job status(MainframeConnectionEntity connection, Job job) {
        HttpResponse<String> response = send(connection, request(connection, jobUrl(connection, job)).GET().build());
        if (response.statusCode() == 404) return new Job(job.jobName(), job.jobId(), "NOT_FOUND", job.returnCode(), job.url());
        ensure2xx(response, "read job status");
        Job current = job(readJson(response.body()));
        return new Job(first(current.jobName(), job.jobName()), first(current.jobId(), job.jobId()),
                first(current.status(), job.status()), first(current.returnCode(), job.returnCode()),
                first(current.url(), job.url()));
    }

    public List<SpoolFile> spool(MainframeConnectionEntity connection, Job job) {
        HttpResponse<String> listing = send(connection, request(connection, jobUrl(connection, job) + "/files").GET().build());
        ensure2xx(listing, "list job spool files");
        JsonNode root = readJson(listing.body());
        List<SpoolFile> out = new ArrayList<>();
        if (!root.isArray()) return out;
        for (JsonNode file : root) {
            int id = file.path("id").asInt();
            String dd = text(file, "ddname");
            String recordsUrl = text(file, "records-url");
            String url = recordsUrl == null || recordsUrl.isBlank()
                    ? jobUrl(connection, job) + "/" + id + "/records"
                    : normalizeReturnedUrl(connection, recordsUrl);
            HttpResponse<String> content = send(connection, request(connection, url)
                    .header("Accept", "text/plain").GET().build());
            ensure2xx(content, "read " + dd + " spool");
            out.add(new SpoolFile(id, dd == null ? "SPOOL" + id : dd, content.body()));
        }
        return out;
    }

    public void cancel(MainframeConnectionEntity connection, Job job) {
        HttpResponse<String> response = send(connection, request(connection, jobUrl(connection, job))
                .header("X-IBM-Job-Modify-Version", "2.0").DELETE().build());
        if (response.statusCode() != 404) ensure2xx(response, "cancel LOAD job");
    }

    public void deleteDatasetQuietly(MainframeConnectionEntity connection, String dataset) {
        try {
            HttpResponse<String> response = send(connection, request(connection, datasetUrl(connection, dataset)).DELETE().build());
            if (response.statusCode() != 404) ensure2xx(response, "delete " + dataset);
        } catch (RuntimeException ignored) {
            // Remote names are retained in loader evidence so an operator can clean up after a host-side failure.
        }
    }

    private String normalizeReturnedUrl(MainframeConnectionEntity connection, String value) {
        if (value.startsWith("http://") || value.startsWith("https://")) {
            URI uri = URI.create(value);
            return baseOrigin(connection) + uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        }
        return value.startsWith("/") ? baseOrigin(connection) + value : baseUrl(connection) + "/" + value;
    }

    private String datasetUrl(MainframeConnectionEntity connection, String dataset) {
        if (dataset == null || !dataset.matches("[A-Z#$@][A-Z0-9#$@-]{0,7}(\\.[A-Z#$@][A-Z0-9#$@-]{0,7}){1,7}")) {
            throw ApiException.bad("Invalid generated z/OS data set name: " + dataset);
        }
        return baseUrl(connection) + "/restfiles/ds/" + dataset.replace("#", "%23");
    }

    private String jobUrl(MainframeConnectionEntity connection, Job job) {
        return baseUrl(connection) + "/restjobs/jobs/" + encodePath(job.jobName()) + "/" + encodePath(job.jobId());
    }

    private String baseOrigin(MainframeConnectionEntity connection) {
        if (connection.getHost() == null || connection.getHost().isBlank()) throw ApiException.bad("ZOWE connection needs a host");
        return "https://" + connection.getHost() + ":" + (connection.getPort() == null ? 443 : connection.getPort());
    }

    private String baseUrl(MainframeConnectionEntity connection) {
        String path = connection.getBasePath();
        if (path == null || path.isBlank()) path = "/zosmf";
        if (!path.startsWith("/")) path = "/" + path;
        if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return baseOrigin(connection) + path;
    }

    private HttpRequest.Builder request(MainframeConnectionEntity connection, String url) {
        if (connection.getAuthType() != null && !connection.getAuthType().isBlank()
                && !"BASIC".equalsIgnoreCase(connection.getAuthType())) {
            throw ApiException.bad("Db2 z/OS native LOAD currently requires BASIC z/OSMF authentication");
        }
        String token = Base64.getEncoder().encodeToString(((connection.getUsername() == null ? "" : connection.getUsername())
                + ":" + credentials.password(connection)).getBytes(StandardCharsets.UTF_8));
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(30))
                .header("Authorization", "Basic " + token)
                .header("X-CSRF-ZOSMF-HEADER", "true")
                .header("Accept", "application/json");
    }

    private HttpClient client(MainframeConnectionEntity connection) {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20));
        if (connection.isTrustAllCerts()) {
            if (!properties.getMainframe().isAllowInsecureTls()) {
                throw ApiException.bad("Connection requests trust-all TLS, but insecure mainframe TLS is disabled");
            }
            try {
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(null, new TrustManager[]{trustAll()}, new SecureRandom());
                builder.sslContext(context);
                SSLParameters parameters = new SSLParameters();
                parameters.setEndpointIdentificationAlgorithm("");
                builder.sslParameters(parameters);
            } catch (Exception e) {
                throw ApiException.bad("Could not configure z/OSMF TLS: " + e.getMessage());
            }
        }
        return builder.build();
    }

    private HttpResponse<String> send(MainframeConnectionEntity connection, HttpRequest request) {
        try {
            return client(connection).send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NativeLoadException("z/OSMF request was interrupted", e);
        } catch (Exception e) {
            throw new NativeLoadException("z/OSMF request failed: " + e.getMessage(), e);
        }
    }

    private void ensure2xx(HttpResponse<String> response, String operation) {
        if (response.statusCode() / 100 != 2) {
            throw new NativeLoadException("z/OSMF could not " + operation + " (HTTP " + response.statusCode() + "): "
                    + response.body());
        }
    }

    private JsonNode readJson(String value) {
        try { return json.readTree(value == null || value.isBlank() ? "{}" : value); }
        catch (Exception e) { throw new NativeLoadException("Invalid JSON returned by z/OSMF: " + e.getMessage(), e); }
    }

    private String writeJson(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new NativeLoadException("Could not create z/OSMF request", e); }
    }

    private Job job(JsonNode node) {
        return new Job(text(node, "jobname"), text(node, "jobid"), text(node, "status"),
                text(node, "retcode"), text(node, "url"));
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private String first(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private String encodePath(String value) { return encode(value == null ? "" : value).replace("+", "%20"); }

    private static X509TrustManager trustAll() {
        return new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] chain, String authType) { }
            public void checkServerTrusted(X509Certificate[] chain, String authType) { }
        };
    }

    public record Job(String jobName, String jobId, String status, String returnCode, String url) { }
    public record SpoolFile(int id, String ddName, String content) { }

    static final class RecordFramingInputStream extends InputStream {
        private final BufferedInputStream input;
        private final int maxRecordBytes;
        private byte[] frame = new byte[0];
        private int offset;
        private boolean eof;

        RecordFramingInputStream(Path path, int maxRecordBytes) {
            try { this.input = new BufferedInputStream(new FileInputStream(path.toFile()), 256 * 1024); }
            catch (IOException e) { throw new UncheckedIOException(e); }
            this.maxRecordBytes = maxRecordBytes;
        }

        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        @Override public int read(byte[] target, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (offset >= frame.length && !nextFrame()) return -1;
            int count = Math.min(len, frame.length - offset);
            System.arraycopy(frame, offset, target, off, count);
            offset += count;
            return count;
        }

        private boolean nextFrame() throws IOException {
            if (eof) return false;
            ByteArrayOutputStream row = new ByteArrayOutputStream(512);
            int value;
            while ((value = input.read()) >= 0 && value != '\n') {
                row.write(value);
                if (row.size() > maxRecordBytes + 1) throw new IOException("Input row exceeds z/OS data set LRECL");
            }
            if (value < 0) eof = true;
            byte[] bytes = row.toByteArray();
            if (bytes.length > 0 && bytes[bytes.length - 1] == '\r') bytes = Arrays.copyOf(bytes, bytes.length - 1);
            if (bytes.length == 0 && eof) { close(); return false; }
            if (bytes.length > maxRecordBytes) throw new IOException("Input row exceeds z/OS data set LRECL");
            frame = ByteBuffer.allocate(bytes.length + 4).putInt(bytes.length).put(bytes).array();
            offset = 0;
            return true;
        }

        @Override public void close() throws IOException { input.close(); }
    }
}
