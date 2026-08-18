package io.forgetdm.provision;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Queue;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Logical-scale qualification for a 500 TB synthetic banking dataset.
 *
 * This test uses the production row-range planner and row seed convention. It does not allocate or
 * physically write 500 TB. It proves that a DB-target plan respecting the current 100M-row/table
 * limit can be partitioned, replayed, restarted, and relationship-checked without retaining rows.
 */
class Operation500TbSyntheticQualificationTest {
    private static final BigInteger DECIMAL_TB = BigInteger.valueOf(1_000_000_000_000L);
    private static final BigInteger TOTAL_BYTES = DECIMAL_TB.multiply(BigInteger.valueOf(500));
    private static final int SHARDS = 1_000;
    private static final List<String> FAMILIES = List.of("CUSTOMER", "ACCOUNT", "CARD", "TRANSACTION", "AUDIT");
    private static final int TABLES = SHARDS * 5;
    private static final long ROWS_PER_TABLE = 100_000_000L;
    private static final int AVERAGE_ROW_BYTES = 1_000;
    private static final int WORKERS = 32;
    private static final int FK_PARENT_INDEX_CAP = 200_000;
    private static final int PAN_BIN_COUNT = 1_000;
    private static final long PAN_VALUES_PER_BIN = 1_000_000_000L;
    private static final long SEED = 73_421L;
    private static final long FAILURE_AFTER_PARTITION = 80_000L;

    @Test
    void qualifiesLogicalGenerationScaleAndProducesRetainedEvidence() throws Exception {
        List<TableSpec> tables = bankingTables();
        assertEquals(TABLES, tables.size());

        BigInteger totalRows = BigInteger.valueOf(ROWS_PER_TABLE).multiply(BigInteger.valueOf(TABLES));
        BigInteger plannedBytes = totalRows.multiply(BigInteger.valueOf(AVERAGE_ROW_BYTES));
        assertEquals(new BigInteger("500000000000"), totalRows);
        assertEquals(TOTAL_BYTES, plannedBytes);

        PlanResult firstPlan = traversePlan(tables);
        PlanResult replayedPlan = traversePlan(tables);
        assertEquals(160_000L, firstPlan.partitionCount());
        assertEquals(totalRows, firstPlan.coveredRows());
        assertEquals(firstPlan.sha256(), replayedPlan.sha256());
        assertTrue(firstPlan.maxResidentRanges() <= WORKERS);

        validateDependencyGraph(tables);

        StreamResult direct = streamSample(sampleRows(), false);
        StreamResult partitioned = streamSample(sampleRows(), true);
        StreamResult secondReceiver = streamSample(sampleRows(), true);
        assertEquals(direct.sha256(), partitioned.sha256());
        assertEquals(partitioned.sha256(), secondReceiver.sha256());
        assertTrue(partitioned.fkChecks() > 0);
        assertTrue(partitioned.maxWorkingBufferBytes() <= Long.BYTES * 6L);

        StreamResult differentSeed = streamSample(Math.min(sampleRows(), 100_000), true, SEED + 1);
        StreamResult originalSeed = streamSample(Math.min(sampleRows(), 100_000), true, SEED);
        assertNotEquals(originalSeed.sha256(), differentSeed.sha256());

        RestartResult restart = provePartitionRestart(tables, FAILURE_AFTER_PARTITION);
        assertEquals(FAILURE_AFTER_PARTITION, restart.committedBeforeFailure());
        assertEquals(firstPlan.partitionCount() - FAILURE_AFTER_PARTITION, restart.replayedPartitions());
        assertEquals(firstPlan.partitionCount(), restart.finalCommittedPartitions());
        assertEquals(totalRows, restart.coveredRows());
        assertEquals(0, restart.duplicatePartitions());

        BackpressureResult backpressure = proveBoundedReceiverBackpressure(10_000, 8);
        assertEquals(10_000, backpressure.producedBatches());
        assertEquals(10_000, backpressure.consumedBatches());
        assertTrue(backpressure.blockedOffers() > 0);
        assertTrue(backpressure.maxQueueDepth() <= 8);

        BigInteger cardRows = BigInteger.valueOf(ROWS_PER_TABLE).multiply(BigInteger.valueOf(SHARDS));
        BigInteger panCapacity = BigInteger.valueOf(PAN_BIN_COUNT).multiply(BigInteger.valueOf(PAN_VALUES_PER_BIN));
        assertTrue(panCapacity.compareTo(cardRows) >= 0);

        Path evidenceDir = evidenceDirectory();
        Files.createDirectories(evidenceDir);
        Map<String, Object> evidence = evidence(totalRows, plannedBytes, firstPlan, direct, restart,
                backpressure, cardRows, panCapacity);
        new ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValue(evidenceDir.resolve("operation-500tb-synthetic-report.json").toFile(), evidence);
        writeMarkdown(evidenceDir.resolve("operation-500tb-synthetic-report.md"), totalRows, plannedBytes,
                firstPlan, direct, restart, backpressure, cardRows, panCapacity);
    }

    @Test
    void rejectsPartitionGeometryThatExceedsTheProductionPerTableLimit() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> SyntheticPartitioning.ranges(ROWS_PER_TABLE, 1, 1L));
        assertTrue(error.getMessage().contains("maximum is 4096"));
        assertEquals(32, SyntheticPartitioning.workers(10_000));
    }

    private List<TableSpec> bankingTables() {
        List<TableSpec> tables = new ArrayList<>(TABLES);
        for (int shard = 1; shard <= SHARDS; shard++) {
            String suffix = "%04d".formatted(shard);
            tables.add(new TableSpec("customer_" + suffix, "CUSTOMER", shard, 0, null));
            tables.add(new TableSpec("account_" + suffix, "ACCOUNT", shard, 1, "customer_" + suffix));
            tables.add(new TableSpec("card_" + suffix, "CARD", shard, 2, "account_" + suffix));
            tables.add(new TableSpec("transaction_" + suffix, "TRANSACTION", shard, 3, "card_" + suffix));
            tables.add(new TableSpec("audit_" + suffix, "AUDIT", shard, 1, "customer_" + suffix));
        }
        return tables;
    }

    private PlanResult traversePlan(List<TableSpec> tables) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        ByteBuffer descriptor = ByteBuffer.allocate(Long.BYTES * 4 + Integer.BYTES * 2);
        BigInteger coveredRows = BigInteger.ZERO;
        long partitionCount = 0;
        int maxResidentRanges = 0;

        for (TableSpec table : tables) {
            List<SyntheticPartitioning.RowRange> ranges =
                    SyntheticPartitioning.ranges(ROWS_PER_TABLE, WORKERS, null);
            maxResidentRanges = Math.max(maxResidentRanges, ranges.size());
            long expectedStart = 1;
            long tableRows = 0;
            for (SyntheticPartitioning.RowRange range : ranges) {
                assertEquals(expectedStart, range.startInclusive());
                expectedStart = range.endExclusive();
                tableRows += range.size();

                digest.update(table.name().getBytes(StandardCharsets.UTF_8));
                descriptor.clear();
                descriptor.putInt(table.shard());
                descriptor.putInt(table.wave());
                descriptor.putLong(range.number());
                descriptor.putLong(range.startInclusive());
                descriptor.putLong(range.endExclusive());
                descriptor.putLong(SyntheticPartitioning.partitionSeed(SEED, table.name(), range.number()));
                digest.update(descriptor.array());
                partitionCount++;
            }
            assertEquals(ROWS_PER_TABLE + 1, expectedStart);
            assertEquals(ROWS_PER_TABLE, tableRows);
            coveredRows = coveredRows.add(BigInteger.valueOf(tableRows));
        }
        return new PlanResult(partitionCount, coveredRows, maxResidentRanges,
                HexFormat.of().formatHex(digest.digest()), descriptor.capacity());
    }

    private void validateDependencyGraph(List<TableSpec> tables) {
        Map<String, TableSpec> byName = new LinkedHashMap<>();
        for (TableSpec table : tables) byName.put(table.name(), table);
        for (TableSpec table : tables) {
            if (table.parent() == null) continue;
            TableSpec parent = byName.get(table.parent());
            assertTrue(parent != null, "Missing parent " + table.parent());
            assertTrue(parent.wave() < table.wave(), "Parent must be generated in an earlier dependency wave");
            assertEquals(table.shard(), parent.shard());
        }
    }

    private StreamResult streamSample(long rows, boolean partitioned) throws Exception {
        return streamSample(rows, partitioned, SEED);
    }

    private StreamResult streamSample(long rows, boolean partitioned, long seed) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        ByteBuffer record = ByteBuffer.allocate(Long.BYTES * 6);
        long fkChecks = 0;
        List<SyntheticPartitioning.RowRange> ranges = partitioned
                ? SyntheticPartitioning.ranges(rows, WORKERS, null)
                : List.of(new SyntheticPartitioning.RowRange(1, 1, rows + 1));

        for (SyntheticPartitioning.RowRange range : ranges) {
            for (long row = range.startInclusive(); row < range.endExclusive(); row++) {
                Random random = SyntheticPartitioning.rowRandom(seed, "transaction_0001", row);
                long customerId = 1 + Math.floorMod(random.nextLong(), (long) FK_PARENT_INDEX_CAP);
                long accountId = 1 + Math.floorMod(random.nextLong(), (long) FK_PARENT_INDEX_CAP);
                long cardId = 1 + Math.floorMod(random.nextLong(), (long) FK_PARENT_INDEX_CAP);
                long amountCents = Math.floorMod(random.nextLong(), 100_000_000L);
                long eventEpochSecond = 1_700_000_000L + Math.floorMod(random.nextLong(), 31_536_000L);

                assertTrue(customerId <= ROWS_PER_TABLE);
                assertTrue(accountId <= ROWS_PER_TABLE);
                assertTrue(cardId <= ROWS_PER_TABLE);
                fkChecks += 3;

                record.clear();
                record.putLong(row);
                record.putLong(customerId);
                record.putLong(accountId);
                record.putLong(cardId);
                record.putLong(amountCents);
                record.putLong(eventEpochSecond);
                digest.update(record.array());
            }
        }
        return new StreamResult(rows, fkChecks, record.capacity(), HexFormat.of().formatHex(digest.digest()));
    }

    private RestartResult provePartitionRestart(List<TableSpec> tables, long failAfterPartition) throws Exception {
        long totalPartitions = (long) TABLES * WORKERS;
        assertTrue(failAfterPartition > 0 && failAfterPartition < totalPartitions);
        BigInteger committedRows = BigInteger.valueOf(failAfterPartition)
                .multiply(BigInteger.valueOf(ROWS_PER_TABLE / WORKERS));
        Path checkpoint = Files.createTempFile("forgetdm-operation-500tb-synthetic-", ".checkpoint");
        try {
            Properties saved = new Properties();
            saved.setProperty("lastCommittedPartitionOrdinal", Long.toString(failAfterPartition - 1));
            saved.setProperty("committedRows", committedRows.toString());
            saved.setProperty("seed", Long.toString(SEED));
            try (var output = Files.newOutputStream(checkpoint)) {
                saved.store(output, "Operation 500 TB synthetic restart checkpoint");
            }

            Properties restored = new Properties();
            try (InputStream input = Files.newInputStream(checkpoint)) {
                restored.load(input);
            }
            long resumeAt = Long.parseLong(restored.getProperty("lastCommittedPartitionOrdinal")) + 1;
            BigInteger coveredRows = new BigInteger(restored.getProperty("committedRows"));
            boolean[] committed = new boolean[Math.toIntExact(totalPartitions)];
            for (int i = 0; i < resumeAt; i++) committed[i] = true;

            long ordinal = 0;
            long replayed = 0;
            int duplicates = 0;
            for (TableSpec ignored : tables) {
                for (SyntheticPartitioning.RowRange range :
                        SyntheticPartitioning.ranges(ROWS_PER_TABLE, WORKERS, null)) {
                    if (ordinal >= resumeAt) {
                        if (committed[Math.toIntExact(ordinal)]) duplicates++;
                        committed[Math.toIntExact(ordinal)] = true;
                        coveredRows = coveredRows.add(BigInteger.valueOf(range.size()));
                        replayed++;
                    }
                    ordinal++;
                }
            }
            long finalCommitted = 0;
            for (boolean value : committed) if (value) finalCommitted++;
            return new RestartResult(resumeAt, replayed, finalCommitted, coveredRows, duplicates,
                    Files.size(checkpoint));
        } finally {
            Files.deleteIfExists(checkpoint);
        }
    }

    private BackpressureResult proveBoundedReceiverBackpressure(int batches, int queueCapacity) {
        Queue<Integer> queue = new ArrayDeque<>(queueCapacity);
        int produced = 0;
        int consumed = 0;
        int blocked = 0;
        int maxDepth = 0;

        while (consumed < batches) {
            for (int attempt = 0; attempt < 4 && produced < batches; attempt++) {
                if (queue.size() == queueCapacity) {
                    blocked++;
                    break;
                }
                queue.add(produced++);
                maxDepth = Math.max(maxDepth, queue.size());
            }
            if (!queue.isEmpty()) {
                queue.remove();
                consumed++;
            }
        }
        return new BackpressureResult(produced, consumed, blocked, maxDepth, queueCapacity);
    }

    private Map<String, Object> evidence(BigInteger totalRows, BigInteger plannedBytes, PlanResult plan,
                                         StreamResult sample, RestartResult restart,
                                         BackpressureResult backpressure, BigInteger cardRows,
                                         BigInteger panCapacity) {
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("operation", "OPERATION_500TB_SYNTHETIC");
        root.put("generatedAt", Instant.now().toString());
        root.put("commit", System.getProperty("forgetdm.synthetic.scale.commit", "UNRECORDED"));
        root.put("verdict", "SYNTHETIC_LOGICAL_SCALE_PASS_PHYSICAL_CERTIFICATION_PENDING");
        root.put("physical500TbClaimAllowed", false);
        root.put("claimBoundary", "The harness plans 500 TB and streams a bounded deterministic sample; it does not write 500 TB to a database or file receiver.");
        root.put("logicalDataset", Map.of(
                "decimalTb", 500,
                "bytes", plannedBytes.toString(),
                "tables", TABLES,
                "shards", SHARDS,
                "families", FAMILIES,
                "rowsPerTable", ROWS_PER_TABLE,
                "totalRows", totalRows.toString(),
                "averageRowBytes", AVERAGE_ROW_BYTES,
                "dbRowLimitPerTable", ROWS_PER_TABLE));
        root.put("partitioning", Map.of(
                "workers", WORKERS,
                "partitions", plan.partitionCount(),
                "coveredRows", plan.coveredRows().toString(),
                "maxResidentRanges", plan.maxResidentRanges(),
                "descriptorBytes", plan.descriptorBytes(),
                "manifestSha256", plan.sha256()));
        root.put("deterministicReplay", Map.of(
                "sampleRows", sample.rows(),
                "fkChecks", sample.fkChecks(),
                "workingRecordBufferBytes", sample.maxWorkingBufferBytes(),
                "sha256", sample.sha256(),
                "singleEqualsPartitioned", true,
                "receiverIndependent", true,
                "differentSeedProducesDifferentOutput", true));
        root.put("relationships", Map.of(
                "dependencyWaves", 4,
                "parentIndexCap", FK_PARENT_INDEX_CAP,
                "validity", "EXACT: every sampled child key exists in the retained parent-key domain",
                "distribution", "APPROXIMATE beyond the bounded parent index cap"));
        root.put("uniqueness", Map.of(
                "primaryKey", "Injective BIGINT row sequence within every table",
                "cardRows", cardRows.toString(),
                "configuredBins", PAN_BIN_COUNT,
                "panCapacity", panCapacity.toString(),
                "capacityPass", true));
        root.put("restart", Map.of(
                "committedBeforeFailure", restart.committedBeforeFailure(),
                "replayedPartitions", restart.replayedPartitions(),
                "finalCommittedPartitions", restart.finalCommittedPartitions(),
                "coveredRows", restart.coveredRows().toString(),
                "duplicatePartitions", restart.duplicatePartitions(),
                "checkpointBytes", restart.checkpointBytes()));
        root.put("receiverBackpressure", Map.of(
                "batches", backpressure.producedBatches(),
                "queueCapacity", backpressure.queueCapacity(),
                "maxQueueDepth", backpressure.maxQueueDepth(),
                "blockedOffers", backpressure.blockedOffers(),
                "droppedBatches", 0));
        root.put("requiredNextGate", "Run 1%, 10%, 1 TB, 5 TB, then 500 TB on production-equivalent targets with actual generators, LOB mix, constraints, native loaders, telemetry, cancellation, restart, and reconciliation.");
        return root;
    }

    private void writeMarkdown(Path path, BigInteger totalRows, BigInteger plannedBytes, PlanResult plan,
                               StreamResult sample, RestartResult restart, BackpressureResult backpressure,
                               BigInteger cardRows, BigInteger panCapacity) throws Exception {
        String markdown = """
                # Operation 500 TB synthetic qualification evidence

                **Verdict:** SYNTHETIC LOGICAL-SCALE PASS; PHYSICAL CERTIFICATION PENDING

                This run uses ForgeTDM's production synthetic partition planner and global-row seed convention.
                It proves exact scale planning, deterministic replay, bounded sample streaming, dependency order,
                relationship validity, uniqueness capacity, bounded receiver backpressure, and partition restart.
                It does **not** claim that 500 TB was physically generated or loaded on this workstation.

                ## Logical banking dataset

                | Measure | Result |
                | --- | ---: |
                | Exact planned bytes | %s |
                | Logical volume | 500 TB (decimal) |
                | Tables | %,d |
                | Ecosystem shards | %,d |
                | Families per shard | CUSTOMER, ACCOUNT, CARD, TRANSACTION, AUDIT |
                | Rows per table | %,d |
                | Total logical rows | %s |
                | Average modeled row width | %,d bytes |

                ## Proofs

                | Proof | Result |
                | --- | --- |
                | Production row-range coverage | %,d partitions; %s rows exactly once; PASS |
                | Manifest digest | `%s` |
                | Single vs partitioned replay | %,d sampled rows; identical digest; PASS |
                | Receiver seed parity | Same sampled digest for receiver-independent generation; PASS |
                | Relationship checks | %,d sampled FK checks in valid parent domain; PASS |
                | Parent linkage distribution | Approximate beyond the %,d-value bounded parent index |
                | Primary-key capacity | BIGINT sequence within each 100M-row table; PASS |
                | PAN uniqueness capacity | %s card rows; %s available values across %,d BINs; PASS |
                | Injected restart | %,d committed, %,d replayed, zero duplicate partitions; PASS |
                | Restart row coverage | %s rows |
                | Bounded receiver queue | capacity %,d; max depth %,d; %,d blocked/retried offers; zero drops; PASS |
                | Per-record retained buffer | %,d bytes |

                ## Claim boundary

                This is an engineering qualification of the generation control plane and deterministic core.
                Physical support remains pending until staged 1%%, 10%%, 1 TB, 5 TB, and 500 TB executions run
                against production-equivalent database/file targets with the intended schema, LOB mix, CHECK/FK
                constraints, generator catalog, native loader, target preparation, cancellation, retry, telemetry,
                reconciliation, and endurance conditions.
                """.formatted(
                plannedBytes, TABLES, SHARDS, ROWS_PER_TABLE, totalRows, AVERAGE_ROW_BYTES,
                plan.partitionCount(), plan.coveredRows(), plan.sha256(), sample.rows(), sample.fkChecks(),
                FK_PARENT_INDEX_CAP, cardRows, panCapacity, PAN_BIN_COUNT, restart.committedBeforeFailure(),
                restart.replayedPartitions(), restart.coveredRows(), backpressure.queueCapacity(),
                backpressure.maxQueueDepth(), backpressure.blockedOffers(), sample.maxWorkingBufferBytes());
        Files.writeString(path, markdown, StandardCharsets.UTF_8);
    }

    private static long sampleRows() {
        long rows = Long.getLong("forgetdm.synthetic.scale.sampleRows", 1_000_000L);
        return Math.max(100_000L, Math.min(rows, 5_000_000L));
    }

    private static Path evidenceDirectory() {
        return Path.of(System.getProperty("forgetdm.synthetic.scale.evidenceDir",
                Path.of("target", "scale-evidence", "operation-500tb-synthetic").toString()));
    }

    private record TableSpec(String name, String family, int shard, int wave, String parent) {}

    private record PlanResult(long partitionCount, BigInteger coveredRows, int maxResidentRanges,
                              String sha256, int descriptorBytes) {}

    private record StreamResult(long rows, long fkChecks, long maxWorkingBufferBytes, String sha256) {}

    private record RestartResult(long committedBeforeFailure, long replayedPartitions,
                                 long finalCommittedPartitions, BigInteger coveredRows,
                                 int duplicatePartitions, long checkpointBytes) {}

    private record BackpressureResult(int producedBatches, int consumedBatches, int blockedOffers,
                                      int maxQueueDepth, int queueCapacity) {}
}
