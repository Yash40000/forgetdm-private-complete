package io.forgetdm.mainframe;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/datasets/{datasetId}/mainframe-assets")
public class DataScopeMainframeAssetController {
    private final DataScopeMainframeAssetService assets;
    private final DataScopeMainframeFieldMappingService mappings;
    private final DataScopeMainframeRunService runs;

    public DataScopeMainframeAssetController(DataScopeMainframeAssetService assets,
                                             DataScopeMainframeFieldMappingService mappings,
                                             DataScopeMainframeRunService runs) {
        this.assets = assets;
        this.mappings = mappings;
        this.runs = runs;
    }

    @GetMapping
    public List<DataScopeMainframeAssetEntity> list(@PathVariable Long datasetId) {
        return assets.list(datasetId);
    }

    @PostMapping
    public DataScopeMainframeAssetEntity create(@PathVariable Long datasetId,
                                                 @RequestBody DataScopeMainframeAssetEntity body) {
        return assets.create(datasetId, body);
    }

    @PutMapping("/{assetId}")
    public DataScopeMainframeAssetEntity update(@PathVariable Long datasetId, @PathVariable Long assetId,
                                                 @RequestBody DataScopeMainframeAssetEntity body) {
        return assets.update(datasetId, assetId, body);
    }

    @DeleteMapping("/{assetId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long datasetId, @PathVariable Long assetId) {
        assets.delete(datasetId, assetId);
    }

    @PostMapping("/{assetId}/resolve")
    public List<DataScopeMainframeAssetService.ResolvedFile> resolve(@PathVariable Long datasetId,
                                                                     @PathVariable Long assetId) {
        return assets.resolve(datasetId, assetId);
    }

    @GetMapping("/{assetId}/field-mappings")
    public List<DataScopeMainframeFieldMappingEntity> fieldMappings(@PathVariable Long datasetId,
                                                                     @PathVariable Long assetId,
                                                                     @RequestParam Long policyId) {
        return mappings.list(datasetId, assetId, policyId);
    }

    @PutMapping("/{assetId}/field-mappings")
    public List<DataScopeMainframeFieldMappingEntity> replaceFieldMappings(
            @PathVariable Long datasetId, @PathVariable Long assetId, @RequestParam Long policyId,
            @RequestBody List<DataScopeMainframeFieldMappingEntity> body) {
        return mappings.replace(datasetId, assetId, policyId, body);
    }

    @PostMapping("/run")
    public Map<String, Object> run(@PathVariable Long datasetId,
                                   @RequestBody(required = false) DataScopeMainframeRunService.RunRequest body) {
        return runs.launch(datasetId, body, null, null);
    }
}
