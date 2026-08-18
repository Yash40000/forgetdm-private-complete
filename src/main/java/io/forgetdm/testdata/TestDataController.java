package io.forgetdm.testdata;

import io.forgetdm.common.ApiException;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Tester-first self-service test data. Routes are governed by the provisioning permission family in
 * AccessControlFilter (read = provision.read, writes = provision.run).
 */
@RestController
@RequestMapping("/api/test-data")
public class TestDataController {

    private final TestDataService svc;

    public TestDataController(TestDataService svc) { this.svc = svc; }

    /** The business-asset catalog the tester can ask for. */
    @GetMapping("/recipes")
    public List<TdRecipeEntity> recipes() { return svc.catalog(); }

    /** Submit a plain-language request → returns the interpreted, confirmable plan. */
    @PostMapping("/requests")
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        String text = str(body.get("request"));
        String env = str(body.get("environment"));
        String purpose = str(body.get("purpose"));
        Integer qty = body.get("quantity") == null ? null : Integer.valueOf(String.valueOf(body.get("quantity")));
        return svc.createRequest(text, env, qty, purpose);
    }

    /** Confirm the plan → provisions the data and returns the receipt. */
    @PostMapping("/requests/{id}/confirm")
    public Map<String, Object> confirm(@PathVariable Long id) { return svc.confirm(id); }

    @PostMapping("/requests/{id}/reserve")
    public Map<String, Object> reserve(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        return svc.reserve(id, body == null ? null : str(body.get("purpose")));
    }

    @PostMapping("/requests/{id}/teardown")
    public Map<String, Object> teardown(@PathVariable Long id) { return svc.teardown(id); }

    @GetMapping("/requests")
    public List<Map<String, Object>> mine() { return svc.listMine(); }

    @GetMapping("/requests/{id}")
    public Map<String, Object> get(@PathVariable Long id) { return svc.get(id); }

    @DeleteMapping("/requests/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        svc.delete(id);
        return Map.of("deleted", true);
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o);
        return s.isBlank() ? null : s;
    }
}
