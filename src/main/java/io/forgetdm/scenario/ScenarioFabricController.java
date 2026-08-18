package io.forgetdm.scenario;

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

@RestController
@RequestMapping("/api/scenario-fabric")
public class ScenarioFabricController {
    private final ScenarioFabricService scenarios;

    public ScenarioFabricController(ScenarioFabricService scenarios) {
        this.scenarios = scenarios;
    }

    @GetMapping("/domains")
    public List<ScenarioFabricService.DomainSummary> domains() {
        return scenarios.domains();
    }

    @PostMapping("/domains")
    public ScenarioFabricService.DomainDetail publish(
            @RequestBody ScenarioFabricService.PublishDomain request) {
        return scenarios.publish(request);
    }

    @GetMapping("/domains/{id}")
    public ScenarioFabricService.DomainDetail domain(@PathVariable long id) {
        return scenarios.domain(id);
    }

    @PostMapping("/domains/{id}/assets")
    public ScenarioFabricService.DomainAsset bindAsset(
            @PathVariable long id,
            @RequestBody ScenarioFabricService.AssetRequest request) {
        return scenarios.bindAsset(id, request);
    }

    @DeleteMapping("/domains/{id}/assets/{assetId}")
    public void unbindAsset(@PathVariable long id, @PathVariable long assetId) {
        scenarios.unbindAsset(id, assetId);
    }

    @GetMapping("/blueprints")
    public List<ScenarioFabricService.BlueprintView> blueprints(
            @RequestParam(required = false) Long domainId) {
        return scenarios.blueprints(domainId);
    }

    @PostMapping("/domains/{domainId}/blueprints")
    public ScenarioFabricService.BlueprintView createBlueprint(
            @PathVariable long domainId,
            @RequestBody ScenarioFabricService.BlueprintRequest request) {
        return scenarios.createBlueprint(domainId, request);
    }

    @PostMapping("/domains/{domainId}/validation-examples")
    public List<ScenarioFabricService.BlueprintView> loadValidationExamples(
            @PathVariable long domainId) {
        return scenarios.loadValidationExamples(domainId);
    }

    @PutMapping("/blueprints/{id}")
    public ScenarioFabricService.BlueprintView updateBlueprint(
            @PathVariable long id,
            @RequestBody ScenarioFabricService.BlueprintRequest request) {
        return scenarios.updateBlueprint(id, request);
    }

    @GetMapping("/missions")
    public List<ScenarioFabricService.MissionView> missions() {
        return scenarios.missions();
    }

    @PostMapping("/missions")
    public ScenarioFabricService.MissionView createMission(
            @RequestBody ScenarioFabricService.MissionRequest request) {
        return scenarios.createMission(request);
    }

    @GetMapping("/missions/{id}")
    public ScenarioFabricService.MissionView mission(@PathVariable String id) {
        return scenarios.mission(id);
    }

    @PostMapping("/missions/{id}/launch")
    public ScenarioFabricService.MissionView launch(@PathVariable String id) {
        return scenarios.launch(id);
    }

    @PostMapping("/missions/{id}/refresh")
    public ScenarioFabricService.MissionView refresh(@PathVariable String id) {
        return scenarios.refresh(id);
    }
}
