package io.forgetdm.topology;

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
@RequestMapping("/api/topologies")
public class TopologyController {
    private final TopologyService topologies;
    private final TopologyDiscoveryService discovery;
    private final TopologySampleService samples;

    public TopologyController(TopologyService topologies, TopologyDiscoveryService discovery,
                              TopologySampleService samples) {
        this.topologies = topologies;
        this.discovery = discovery;
        this.samples = samples;
    }

    @GetMapping
    public List<TopologyService.TopologySummary> list() {
        return topologies.list();
    }

    @PostMapping
    public TopologyService.TopologySummary create(@RequestBody TopologyService.CreateTopology request) {
        return topologies.create(request);
    }

    @PostMapping("/sample")
    public TopologySampleService.SampleResult sample() {
        return samples.create();
    }

    @GetMapping("/{id}")
    public TopologyService.TopologySummary get(@PathVariable long id) {
        return topologies.get(id);
    }

    @PutMapping("/{id}")
    public TopologyService.TopologySummary update(@PathVariable long id,
                                                  @RequestBody TopologyService.UpdateTopology request) {
        return topologies.update(id, request);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        topologies.delete(id);
    }

    @GetMapping("/{id}/sources")
    public List<TopologyService.SourceBinding> sources(@PathVariable long id) {
        return topologies.sources(id);
    }

    @PostMapping("/{id}/sources")
    public TopologyService.SourceBinding attachSource(@PathVariable long id,
                                                      @RequestBody TopologyService.AttachSource request) {
        return topologies.attachSource(id, request);
    }

    @DeleteMapping("/{id}/sources/{bindingId}")
    public void detachSource(@PathVariable long id, @PathVariable long bindingId) {
        topologies.detachSource(id, bindingId);
    }

    @PostMapping("/{id}/discover")
    public TopologyService.DiscoveryOperation discover(@PathVariable long id) {
        return discovery.start(id);
    }

    @GetMapping("/{id}/discovery/latest")
    public TopologyService.DiscoveryOperation latestDiscovery(@PathVariable long id) {
        return topologies.latestOperation(id);
    }

    @GetMapping("/{id}/discovery/{operationId}")
    public TopologyService.DiscoveryOperation discovery(@PathVariable long id, @PathVariable long operationId) {
        return discovery.operation(id, operationId);
    }

    @PostMapping("/{id}/discovery/{operationId}/cancel")
    public TopologyService.DiscoveryOperation cancelDiscovery(@PathVariable long id,
                                                              @PathVariable long operationId) {
        return discovery.cancel(id, operationId);
    }

    @GetMapping("/{id}/graph")
    public TopologyService.GraphSnapshot graph(@PathVariable long id,
                                               @RequestParam(required = false) String q,
                                               @RequestParam(required = false) Long sourceBindingId,
                                               @RequestParam(required = false) Integer limit) {
        return topologies.graph(id, q, sourceBindingId, limit);
    }

    @GetMapping("/{id}/nodes/{nodeId}/columns")
    public List<TopologyService.ColumnSnapshot> columns(@PathVariable long id, @PathVariable long nodeId) {
        return topologies.columns(id, nodeId);
    }

    @PutMapping("/{id}/edges/{edgeId}")
    public TopologyService.GraphEdge reviewEdge(@PathVariable long id, @PathVariable long edgeId,
                                                @RequestBody TopologyService.EdgeDecision decision) {
        return topologies.reviewEdge(id, edgeId, decision);
    }

    @GetMapping("/{id}/versions")
    public List<TopologyService.TopologyVersion> versions(@PathVariable long id) {
        return topologies.versions(id);
    }
}
