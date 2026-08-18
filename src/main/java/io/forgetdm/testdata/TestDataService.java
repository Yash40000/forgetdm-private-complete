package io.forgetdm.testdata;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.config.ForgeProps;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.security.OwnershipGuard;
import io.forgetdm.testdata.TdDto.HowToAccess;
import io.forgetdm.testdata.TdDto.Plan;
import io.forgetdm.testdata.TdDto.Receipt;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tester-first self-service: turn a plain-language request into a confirmable plan, provision the
 * data, and return a plain-language receipt. Governed by the provisioning permission family +
 * object ownership; every step audited.
 */
@Service
public class TestDataService {

    private final TdRecipeRepository recipes;
    private final TdRequestRepository requests;
    private final TdInterpreter interpreter;
    private final TdProvisioner provisioner;
    private final DataSourceService dataSources;
    private final ForgeProps props;
    private final OwnershipGuard ownership;
    private final AuditService audit;
    private final ObjectMapper json;

    public TestDataService(TdRecipeRepository recipes, TdRequestRepository requests, TdInterpreter interpreter,
                           TdProvisioner provisioner, DataSourceService dataSources, ForgeProps props,
                           OwnershipGuard ownership, AuditService audit, ObjectMapper json) {
        this.recipes = recipes;
        this.requests = requests;
        this.interpreter = interpreter;
        this.provisioner = provisioner;
        this.dataSources = dataSources;
        this.props = props;
        this.ownership = ownership;
        this.audit = audit;
        this.json = json;
    }

    // ------------------------------------------------------------------ catalog

    public List<TdRecipeEntity> catalog() {
        return recipes.findAllByOrderBySortOrderAsc();
    }

    // ------------------------------------------------------------------ request → plan

    public Map<String, Object> createRequest(String text, String environment, Integer quantity, String purpose) {
        if (text == null || text.isBlank()) throw ApiException.bad("Please describe the data you need.");
        int q = quantity == null || quantity < 1 ? 1 : Math.min(quantity, 50);
        String env = environment == null || environment.isBlank() ? "SIT" : environment.trim();

        Plan plan = interpreter.interpret(text, catalog(), env, q);

        TdRequestEntity r = new TdRequestEntity();
        r.setRequestText(text.trim());
        r.setEnvironment(env);
        r.setQuantity(q);
        r.setPurpose(blank(purpose));
        r.setStatus("PLANNED");
        r.setPlanJson(write(plan));
        r.setOwnerUserId(ownership.defaultOwnerUserId());
        r.setOwnerUsername(ownership.defaultOwnerUsername());
        r.setOwnerGroupId(ownership.defaultOwnerGroupId());
        r.setVisibility(ownership.defaultVisibility());
        r = requests.save(r);

        audit.log(actor(), "TESTDATA_REQUESTED",
                "request=" + r.getId() + " env=" + env + " assets=" + plan.assets().size());
        return view(r, plan, null);
    }

    // ------------------------------------------------------------------ confirm → provision → receipt

    public Map<String, Object> confirm(Long id) {
        TdRequestEntity r = require(id);
        assertCanTouch(r);
        if ("READY".equals(r.getStatus())) return view(r, readPlan(r), readReceipt(r));

        Plan plan = readPlan(r);
        if (plan == null || plan.assets().isEmpty()) {
            throw ApiException.bad("There's nothing to provision — the request wasn't understood. Edit and try again.");
        }
        DataSourceEntity target = resolveTarget();
        try {
            TdProvisioner.Result res = provisioner.provision(r, plan, catalog(), target);
            HowToAccess how = new HowToAccess(
                    r.getEnvironment() + " (" + target.getName() + ")",
                    "SELECT * FROM " + res.schema() + "." + res.anchorTable()
                            + " WHERE " + res.locatorColumn() + " = '" + res.locatorValue() + "'",
                    "Synthetic & masked — safe for test use.");
            Map<String, Object> gov = new LinkedHashMap<>();
            gov.put("dataOrigin", "SYNTHETIC");
            gov.put("maskingApplied", true);
            gov.put("approvals", "none required");
            Receipt receipt = new Receipt(r.getId(), "READY", plan.summary(), r.getEnvironment(),
                    res.objects(), how, gov, "TD-" + r.getId(), actor());

            r.setStatus("READY");
            r.setError(null);
            r.setReceiptJson(write(receipt.asMap()));
            requests.save(r);
            audit.log(actor(), "TESTDATA_PROVISIONED",
                    "request=" + r.getId() + " objects=" + res.objects().size() + " target=" + target.getName());
            return view(r, plan, receipt.asMap());
        } catch (RuntimeException e) {
            r.setStatus("FAILED");
            r.setError(e.getMessage());
            requests.save(r);
            audit.log(actor(), "TESTDATA_FAILED", "request=" + r.getId() + " error=" + e.getMessage());
            throw e;
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /** Reserve the provisioned set for a test case (records the purpose; data is untouched). */
    public Map<String, Object> reserve(Long id, String purpose) {
        TdRequestEntity r = require(id);
        assertCanTouch(r);
        if (purpose != null && !purpose.isBlank()) r.setPurpose(purpose.trim());
        requests.save(r);
        audit.log(actor(), "TESTDATA_RESERVED", "request=" + id + " purpose=" + r.getPurpose());
        return view(r, readPlan(r), readReceipt(r));
    }

    /** Tear down the provisioned records (no orphans) and mark the request torn down. */
    public Map<String, Object> teardown(Long id) {
        TdRequestEntity r = require(id);
        assertCanTouch(r);
        if (!"READY".equals(r.getStatus())) throw ApiException.bad("Only provisioned requests can be torn down.");
        List<Long> customerIds = customerIdsFrom(readReceipt(r));
        int removed = provisioner.teardown(resolveTarget(), catalog(), customerIds);
        r.setStatus("TORN_DOWN");
        requests.save(r);
        audit.log(actor(), "TESTDATA_TORNDOWN", "request=" + id + " removedCustomers=" + removed);
        return view(r, readPlan(r), readReceipt(r));
    }

    /** Delete the request record (tearing down any live data first). */
    public void delete(Long id) {
        TdRequestEntity r = require(id);
        assertCanTouch(r);
        if ("READY".equals(r.getStatus())) {
            try { teardown(id); } catch (RuntimeException ignored) { /* best effort cleanup */ }
        }
        requests.delete(r);
        audit.log(actor(), "TESTDATA_DELETED", "request=" + id);
    }

    private List<Long> customerIdsFrom(Map<String, Object> receipt) {
        List<Long> ids = new java.util.ArrayList<>();
        if (receipt == null) return ids;
        Object prov = receipt.get("provisioned");
        if (prov instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && "Customer".equals(m.get("type"))) {
                    try { ids.add(Long.parseLong(String.valueOf(m.get("id")).replaceAll("[^0-9]", ""))); }
                    catch (Exception ignored) { }
                }
            }
        }
        return ids;
    }

    // ------------------------------------------------------------------ read

    public Map<String, Object> get(Long id) {
        TdRequestEntity r = require(id);
        assertCanTouch(r);
        return view(r, readPlan(r), readReceipt(r));
    }

    public List<Map<String, Object>> listMine() {
        String me = actor();
        List<TdRequestEntity> rows = "system".equals(me)
                ? requests.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 50))
                : requests.findByOwnerUsernameOrderByCreatedAtDesc(me, PageRequest.of(0, 50));
        return rows.stream().map(this::summaryView).toList();
    }

    // ------------------------------------------------------------------ helpers

    private DataSourceEntity resolveTarget() {
        Long configured = props.getTestData().getTargetDataSourceId();
        if (configured != null) return dataSources.get(configured);
        return dataSources.list().stream()
                .filter(d -> d.getKind() != null && d.getKind().toLowerCase(Locale.ROOT).contains("postgres"))
                .findFirst()
                .orElseThrow(() -> ApiException.bad(
                        "No PostgreSQL target is available for self-service. Set forgetdm.test-data.target-data-source-id."));
    }

    private TdRequestEntity require(Long id) {
        return requests.findById(id).orElseThrow(() -> ApiException.notFound("Request not found"));
    }

    private void assertCanTouch(TdRequestEntity r) {
        ownership.assertCanSee("test data request", r.getId(),
                r.getOwnerUserId(), r.getOwnerGroupId(), r.getVisibility());
    }

    private String actor() {
        return ownership.caller().map(p -> p.username()).orElse("system");
    }

    private Map<String, Object> view(TdRequestEntity r, Plan plan, Map<String, Object> receipt) {
        Map<String, Object> m = summaryView(r);
        m.put("plan", plan);
        m.put("receipt", receipt);
        return m;
    }

    private Map<String, Object> summaryView(TdRequestEntity r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("requestText", r.getRequestText());
        m.put("environment", r.getEnvironment());
        m.put("purpose", r.getPurpose());
        m.put("quantity", r.getQuantity());
        m.put("status", r.getStatus());
        m.put("error", r.getError());
        m.put("createdAt", r.getCreatedAt());
        Plan plan = readPlan(r);
        m.put("summary", plan == null ? null : plan.summary());
        return m;
    }

    private Plan readPlan(TdRequestEntity r) {
        if (r.getPlanJson() == null) return null;
        try { return json.readValue(r.getPlanJson(), Plan.class); } catch (Exception e) { return null; }
    }

    private Map<String, Object> readReceipt(TdRequestEntity r) {
        if (r.getReceiptJson() == null) return null;
        try { return json.readValue(r.getReceiptJson(), new TypeReference<Map<String, Object>>() {}); }
        catch (Exception e) { return null; }
    }

    private String write(Object o) {
        try { return json.writeValueAsString(o); } catch (Exception e) { return null; }
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
