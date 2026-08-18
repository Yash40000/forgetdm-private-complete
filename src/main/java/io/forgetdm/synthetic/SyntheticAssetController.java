package io.forgetdm.synthetic;

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

@RestController
@RequestMapping("/api/synthetic/assets")
public class SyntheticAssetController {
    private final SyntheticAssetService assets;

    public SyntheticAssetController(SyntheticAssetService assets) {
        this.assets = assets;
    }

    @GetMapping("/types")
    public List<SyntheticAssetService.AssetType> types() {
        return assets.types();
    }

    @GetMapping("/plugins")
    public List<Map<String, Object>> plugins() {
        return assets.plugins();
    }

    @GetMapping
    public List<SyntheticAssetService.AssetSummary> list(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String query) {
        return assets.list(type, status, query);
    }

    @PostMapping
    public SyntheticAssetService.AssetDetail create(
            @RequestBody SyntheticAssetService.AssetRequest request) {
        return assets.create(request);
    }

    @GetMapping("/{id}")
    public SyntheticAssetService.AssetDetail get(@PathVariable String id) {
        return assets.get(id);
    }

    @PutMapping("/{id}")
    public SyntheticAssetService.AssetDetail update(
            @PathVariable String id,
            @RequestBody SyntheticAssetService.AssetRequest request) {
        return assets.update(id, request);
    }

    @PostMapping("/{id}/publish")
    public SyntheticAssetService.AssetDetail publish(@PathVariable String id) {
        return assets.publish(id);
    }

    @PostMapping("/{id}/clone")
    public SyntheticAssetService.AssetDetail cloneAsset(
            @PathVariable String id,
            @RequestBody(required = false) SyntheticAssetService.CloneRequest request) {
        return assets.cloneAsset(id, request);
    }

    @PostMapping("/{id}/status")
    public SyntheticAssetService.AssetDetail status(
            @PathVariable String id,
            @RequestBody SyntheticAssetService.StatusRequest request) {
        return assets.changeStatus(id, request);
    }

    @DeleteMapping("/{id}")
    public SyntheticAssetService.AssetDetail archive(@PathVariable String id) {
        return assets.changeStatus(id, new SyntheticAssetService.StatusRequest("ARCHIVED"));
    }

    @GetMapping("/{id}/versions")
    public List<SyntheticAssetService.VersionSummary> versions(@PathVariable String id) {
        return assets.versions(id);
    }

    @GetMapping("/{id}/compare")
    public Map<String, Object> compare(@PathVariable String id,
                                       @RequestParam int from,
                                       @RequestParam int to) {
        return assets.compare(id, from, to);
    }

    @GetMapping("/{id}/impact")
    public List<SyntheticAssetService.ImpactItem> impact(@PathVariable String id) {
        return assets.impact(id);
    }

    @PostMapping("/{id}/compile")
    public SyntheticAssetService.CompiledScenario compile(
            @PathVariable String id,
            @RequestBody(required = false) SyntheticAssetService.LaunchRequest request) {
        return assets.compile(id, request == null ? null : request.version());
    }

    @PostMapping("/{id}/launch")
    public Map<String, Object> launch(
            @PathVariable String id,
            @RequestBody(required = false) SyntheticAssetService.LaunchRequest request) {
        return assets.launch(id, request);
    }
}
