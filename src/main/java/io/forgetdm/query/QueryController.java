package io.forgetdm.query;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Data Explorer: run a single read-only SELECT against a connected source and preview up to 1000 rows.
 *   POST /api/query/run  { dataSourceId, sql } → { columns, rows, rowCount, truncated, elapsedMs }
 */
@RestController
@RequestMapping("/api/query")
public class QueryController {

    private final QueryService svc;

    public QueryController(QueryService svc) { this.svc = svc; }

    @PostMapping("/run")
    public Map<String, Object> run(@RequestBody JsonNode body) {
        JsonNode idNode = body.path("dataSourceId");
        Long id = (idNode.isMissingNode() || idNode.isNull()) ? null : idNode.asLong();
        return svc.run(id, body.path("sql").asText(null));
    }

    @PostMapping("/execute")
    public Map<String, Object> execute(@RequestBody JsonNode body) {
        JsonNode idNode = body.path("dataSourceId");
        Long id = (idNode.isMissingNode() || idNode.isNull()) ? null : idNode.asLong();
        return svc.execute(id, body.path("sql").asText(null));
    }

    @PostMapping("/table/read")
    public Map<String, Object> readTable(@RequestBody TableReadRequest request) {
        return svc.readTable(request);
    }

    @PostMapping("/table/insert")
    public Map<String, Object> insert(@RequestBody TableInsertRequest request) {
        return svc.insert(request);
    }

    @PostMapping("/table/update")
    public Map<String, Object> update(@RequestBody TableUpdateRequest request) {
        return svc.update(request);
    }

    @PostMapping("/table/delete")
    public Map<String, Object> delete(@RequestBody TableDeleteRequest request) {
        return svc.delete(request);
    }

    public record TableReadRequest(Long dataSourceId, String schema, String table, Integer limit, Integer offset) {}

    public record TableInsertRequest(Long dataSourceId, String schema, String table, Map<String, Object> values) {}

    public record TableUpdateRequest(Long dataSourceId, String schema, String table,
                                     Map<String, Object> keyValues, Map<String, Object> values) {}

    public record TableDeleteRequest(Long dataSourceId, String schema, String table,
                                     Map<String, Object> keyValues) {}
}
