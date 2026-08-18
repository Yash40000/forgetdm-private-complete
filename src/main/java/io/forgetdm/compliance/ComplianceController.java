package io.forgetdm.compliance;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Compliance assurance API.
 *
 * <p>Governed by the {@code compliance.*} permission family: reads need {@code compliance.read},
 * scans and exception requests need {@code compliance.run}, and approving or revoking an exception
 * needs {@code compliance.approve} — segregation of duties is enforced at the route as well as in
 * {@link PiiExceptionService}, so a user who can request an exception cannot also rubber-stamp it.
 */
@RestController
@RequestMapping("/api/compliance")
public class ComplianceController {

    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final ComplianceService compliance;
    private final PiiExceptionService exceptions;
    private final EvidencePackService evidence;

    public ComplianceController(ComplianceService compliance, PiiExceptionService exceptions,
                                EvidencePackService evidence) {
        this.compliance = compliance;
        this.exceptions = exceptions;
        this.evidence = evidence;
    }

    // ---------------------------------------------------------------- posture

    /** Dashboard summary: scan verdicts, exception counts and ledger integrity. */
    @GetMapping("/posture")
    public Map<String, Object> posture() {
        return compliance.posture();
    }

    // ------------------------------------------------------------------ scans

    /**
     * Run an assurance scan.
     *
     * <pre>
     * { "scanType": "FULL|COVERAGE|LEAK|CARDINALITY",
     *   "targetId": 3, "sourceId": 1, "policyId": 7,
     *   "schemaName": "public", "environment": "SIT", "name": "Monthly SIT attestation" }
     * </pre>
     */
    @PostMapping("/scans")
    public Map<String, Object> runScan(@RequestBody JsonNode b) {
        return compliance.runScan(
                text(b, "scanType"),
                id(b, "targetId"),
                id(b, "sourceId"),
                id(b, "policyId"),
                text(b, "schemaName"),
                text(b, "environment"),
                text(b, "name"));
    }

    @GetMapping("/scans")
    public List<Map<String, Object>> listScans(@RequestParam(required = false) String scanType,
                                              @RequestParam(required = false) Long targetId,
                                              @RequestParam(required = false, defaultValue = "50") int limit) {
        return compliance.listScans(scanType, targetId, limit);
    }

    @GetMapping("/scans/{id}")
    public Map<String, Object> getScan(@PathVariable Long id) {
        return compliance.getScan(id);
    }

    @DeleteMapping("/scans/{id}")
    public Map<String, Object> deleteScan(@PathVariable Long id) {
        compliance.deleteScan(id);
        return Map.of("deleted", id);
    }

    // ------------------------------------------------------- subject erasure

    /**
     * Data-subject erasure ("right to be forgotten") search.
     *
     * <pre>{ "subjectValue": "123-45-6789", "piiType": "SSN", "targetId": 3 }</pre>
     *
     * <p>{@code targetId} is optional — omitted, every registered non-production environment is
     * searched. The subject value is used to query but never stored; only its salted hash is kept so
     * the same request can be re-evidenced later.
     */
    @PostMapping("/subject-search")
    public Map<String, Object> subjectSearch(@RequestBody JsonNode b) {
        return compliance.runSubjectSearch(text(b, "subjectValue"), text(b, "piiType"), id(b, "targetId"));
    }

    // ------------------------------------------------------ exception register

    @GetMapping("/exceptions")
    public List<Map<String, Object>> listExceptions() {
        return exceptions.list();
    }

    @GetMapping("/exceptions/{id}")
    public Map<String, Object> getException(@PathVariable Long id) {
        return exceptions.get(id);
    }

    /**
     * Request an exception permitting unmasked production data in a non-production environment.
     *
     * <pre>
     * { "dataSourceId": 3, "environment": "SIT", "scope": "public.customer.ssn",
     *   "piiType": "SSN", "justification": "...", "compensatingControls": "...", "days": 30 }
     * </pre>
     */
    @PostMapping("/exceptions")
    public Map<String, Object> requestException(@RequestBody JsonNode b) {
        return exceptions.request(
                id(b, "dataSourceId"),
                text(b, "environment"),
                text(b, "scope"),
                text(b, "piiType"),
                text(b, "justification"),
                text(b, "compensatingControls"),
                b.hasNonNull("days") ? b.get("days").asInt() : null);
    }

    @PostMapping("/exceptions/{id}/approve")
    public Map<String, Object> approveException(@PathVariable Long id, @RequestBody(required = false) JsonNode b) {
        return exceptions.approve(id, b == null ? null : text(b, "note"));
    }

    @PostMapping("/exceptions/{id}/reject")
    public Map<String, Object> rejectException(@PathVariable Long id, @RequestBody(required = false) JsonNode b) {
        return exceptions.reject(id, b == null ? null : text(b, "reason"));
    }

    @PostMapping("/exceptions/{id}/revoke")
    public Map<String, Object> revokeException(@PathVariable Long id, @RequestBody(required = false) JsonNode b) {
        return exceptions.revoke(id, b == null ? null : text(b, "reason"));
    }

    @DeleteMapping("/exceptions/{id}")
    public Map<String, Object> deleteException(@PathVariable Long id) {
        exceptions.delete(id);
        return Map.of("deleted", id);
    }

    // ---------------------------------------------------------- evidence pack

    /** Compile the auditor evidence pack as JSON (includes the rendered Markdown). */
    @PostMapping("/evidence-pack")
    public Map<String, Object> evidencePack(@RequestBody JsonNode b) {
        return evidence.build(id(b, "targetId"), id(b, "sourceId"), id(b, "policyId"), text(b, "schemaName"));
    }

    /** Download the same pack as a Markdown file, ready to attach to an audit response. */
    @GetMapping("/evidence-pack/download")
    public ResponseEntity<byte[]> downloadEvidencePack(@RequestParam Long targetId,
                                                       @RequestParam(required = false) Long sourceId,
                                                       @RequestParam(required = false) Long policyId,
                                                       @RequestParam(required = false) String schemaName) {
        Map<String, Object> pack = evidence.build(targetId, sourceId, policyId, schemaName);
        byte[] body = String.valueOf(pack.get("markdown")).getBytes(StandardCharsets.UTF_8);
        String filename = "forgetdm-evidence-pack-" + FILE_STAMP.format(Instant.now()) + ".md";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/markdown; charset=UTF-8"))
                .body(body);
    }

    // ---------------------------------------------------------------- helpers

    private static String text(JsonNode b, String field) {
        return b != null && b.hasNonNull(field) && !b.get(field).asText().isBlank()
                ? b.get(field).asText() : null;
    }

    private static Long id(JsonNode b, String field) {
        return b != null && b.hasNonNull(field) ? b.get(field).asLong() : null;
    }
}
