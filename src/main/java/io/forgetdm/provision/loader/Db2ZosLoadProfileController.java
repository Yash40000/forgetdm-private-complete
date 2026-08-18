package io.forgetdm.provision.loader;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/datasources")
public class Db2ZosLoadProfileController {
    private final Db2ZosLoadProfileService profiles;
    private final ZosmfJobClient zosmf;

    public Db2ZosLoadProfileController(Db2ZosLoadProfileService profiles, ZosmfJobClient zosmf) {
        this.profiles = profiles;
        this.zosmf = zosmf;
    }

    @GetMapping("/db2-zos-loader-profiles")
    public List<Db2ZosLoadProfileEntity> list() { return profiles.list(); }

    @GetMapping("/{dataSourceId}/db2-zos-loader-profile")
    public ResponseEntity<Db2ZosLoadProfileEntity> get(@PathVariable Long dataSourceId) {
        return profiles.find(dataSourceId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/{dataSourceId}/db2-zos-loader-profile")
    public Db2ZosLoadProfileEntity save(@PathVariable Long dataSourceId,
                                        @RequestBody Db2ZosLoadProfileEntity input) {
        return profiles.save(dataSourceId, input);
    }

    @DeleteMapping("/{dataSourceId}/db2-zos-loader-profile")
    public Map<String, Object> delete(@PathVariable Long dataSourceId) {
        profiles.delete(dataSourceId);
        return Map.of("deleted", true, "dataSourceId", dataSourceId);
    }

    @PostMapping("/{dataSourceId}/db2-zos-loader-profile/test")
    public Map<String, Object> test(@PathVariable Long dataSourceId) {
        Db2ZosLoadProfileService.ResolvedProfile resolved = profiles.resolve(dataSourceId);
        LinkedHashMap<String, Object> result = new LinkedHashMap<>(zosmf.readiness(resolved.connection()));
        result.put("dataSourceId", dataSourceId);
        result.put("profileId", resolved.profile().getId());
        result.put("subsystem", resolved.profile().getSubsystem());
        result.put("workHlq", resolved.profile().getWorkHlq());
        result.put("procedureName", resolved.profile().getProcedureName());
        result.put("loggingMode", resolved.profile().getLoggingMode());
        result.put("message", "z/OSMF JES is reachable. DSNUTILB authority is verified by the first controlled LOAD run.");
        return result;
    }
}
