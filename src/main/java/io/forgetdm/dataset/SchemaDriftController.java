package io.forgetdm.dataset;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Standalone whole-schema monitoring API. DataScope drift endpoints remain backward compatible. */
@RestController
@RequestMapping("/api/schema-drift/monitors")
public class SchemaDriftController {
    private final SchemaDriftService service;

    public SchemaDriftController(SchemaDriftService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return service.listMonitors();
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody SchemaDriftService.MonitorRequest request) {
        return service.createMonitor(request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        service.deleteMonitor(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}")
    public Map<String, Object> current(@PathVariable long id) {
        service.requireMonitor(id);
        return service.current(id);
    }

    @PostMapping("/{id}/check")
    public Map<String, Object> check(@PathVariable long id) {
        service.requireMonitor(id);
        return service.check(id, "MANUAL");
    }

    @PostMapping("/{id}/baseline")
    public Map<String, Object> baseline(@PathVariable long id,
                                        @RequestBody SchemaDriftService.BaselineRequest request) {
        service.requireMonitor(id);
        return service.createBaseline(id, request);
    }

    @PostMapping("/{id}/accept")
    public Map<String, Object> accept(@PathVariable long id,
                                      @RequestBody SchemaDriftService.AcceptRequest request) {
        service.requireMonitor(id);
        return service.acceptRun(id, request);
    }

    @GetMapping("/{id}/history")
    public List<Map<String, Object>> history(@PathVariable long id,
                                             @RequestParam(defaultValue = "25") int limit) {
        service.requireMonitor(id);
        return service.history(id, limit);
    }

    @GetMapping("/{id}/schedule")
    public Map<String, Object> schedule(@PathVariable long id) {
        service.requireMonitor(id);
        return service.schedule(id);
    }

    @PutMapping("/{id}/schedule")
    public Map<String, Object> schedule(@PathVariable long id,
                                        @RequestBody SchemaDriftService.ScheduleRequest request) {
        service.requireMonitor(id);
        return service.updateSchedule(id, request);
    }
}
