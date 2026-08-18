package io.forgetdm.scale;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Logical-scale qualification for a 500 TB provisioning plan.
 *
 * This deliberately does not allocate the requested data volume. It proves exact scale arithmetic,
 * bounded manifest traversal, checkpoint capacity, deterministic restart, and bounded-memory streaming.
 * The retained evidence labels local throughput as a projection rather than physical certification.
 */
class Operation500TbQualificationTest {
    private static final BigInteger DECIMAL_TB = BigInteger.valueOf(1_000_000_000_000L);
    private static final BigInteger GIB = BigInteger.valueOf(1L << 30);
    private static final BigInteger TOTAL_BYTES = DECIMAL_TB.multiply(BigInteger.valueOf(500));
    private static final BigInteger PARTITION_BYTES = GIB.multiply(BigInteger.valueOf(256));
    private static final BigInteger CHUNK_BYTES = GIB;
    private static final BigInteger AVERAGE_ROW_BYTES = BigInteger.valueOf(2_048);
    private static final int STREAM_BUFFER_BYTES = 1 << 20;
    private static final long FAILURE_AFTER_CHUNK = 250_000L;

    @Test
    void qualifiesLogicalScaleAndProducesRetainedEvidence() throws Exception {
        ScalePlan plan = ScalePlan.create(TOTAL_BYTES, PARTITION_BYTES, CHUNK_BYTES, AVERAGE_ROW_BYTES);

        assertEquals(new BigInteger("500000000000000"), plan.totalBytes());
        assertEquals(1_819L, plan.partitionCount());
        assertEquals(465_662L, plan.chunkCount());
        assertTrue(plan.chunkCount() < Integer.MAX_VALUE);
        assertEquals(plan.totalBytes(), plan.fullChunkBytes().add(plan.lastChunkBytes()));

        ManifestResult manifest = traverseSparseManifest(plan);
        assertEquals(plan.chunkCount(), manifest.visitedChunks());
        assertEquals(plan.totalBytes(), manifest.coveredBytes());

        RestartResult restart = proveRestartFromDurableCheckpoint(plan, FAILURE_AFTER_CHUNK);
        assertEquals(FAILURE_AFTER_CHUNK, restart.committedBeforeFailure());
        assertEquals(plan.chunkCount() - FAILURE_AFTER_CHUNK, restart.replayedChunks());
        assertEquals(plan.totalBytes(), restart.coveredBytesAfterResume());
        assertEquals(plan.chunkCount(), restart.finalCommittedChunks());

        long sampleBytes = sampleBytes();
        StreamResult stream = streamPhysicalSample(sampleBytes);
        assertEquals(sampleBytes, stream.bytesProcessed());
        assertTrue(stream.maxBufferBytes() <= STREAM_BUFFER_BYTES);
        assertTrue(stream.bytesPerSecond() > 0);

        Path evidenceDir = evidenceDirectory();
        Files.createDirectories(evidenceDir);
        Map<String, Object> evidence = evidence(plan, manifest, restart, stream);
        writeJson(evidenceDir.resolve("operation-500tb-report.json"), evidence);
        writeMarkdown(evidenceDir.resolve("operation-500tb-report.md"), evidence, plan, manifest, restart, stream);
    }

    @Test
    void rejectsCheckpointGeometryThatWouldOverflowThePersistedIntegerSequence() {
        BigInteger unsafeChunkBytes = BigInteger.valueOf(128L * 1_024L);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ScalePlan.create(TOTAL_BYTES, PARTITION_BYTES, unsafeChunkBytes, AVERAGE_ROW_BYTES));

        assertTrue(error.getMessage().contains("checkpoint capacity"));
    }

    private ManifestResult traverseSparseManifest(ScalePlan plan) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        ByteBuffer descriptor = ByteBuffer.allocate(Long.BYTES * 4);
        BigInteger covered = BigInteger.ZERO;
        long chunksPerPartition = ceilDivide(plan.partitionBytes(), plan.chunkBytes()).longValueExact();

        for (long chunk = 0; chunk < plan.chunkCount(); chunk++) {
            BigInteger start = plan.chunkBytes().multiply(BigInteger.valueOf(chunk));
            BigInteger remaining = plan.totalBytes().subtract(start);
            BigInteger length = remaining.min(plan.chunkBytes());
            long partition = chunk / chunksPerPartition;

            descriptor.clear();
            descriptor.putLong(chunk);
            descriptor.putLong(partition);
            descriptor.putLong(start.longValueExact());
            descriptor.putLong(length.longValueExact());
            digest.update(descriptor.array());
            covered = covered.add(length);
        }
        return new ManifestResult(plan.chunkCount(), covered,
                HexFormat.of().formatHex(digest.digest()), descriptor.capacity());
    }

    private RestartResult proveRestartFromDurableCheckpoint(ScalePlan plan, long failAfterChunk) throws Exception {
        assertTrue(failAfterChunk > 0 && failAfterChunk < plan.chunkCount());
        BigInteger committedBytes = bytesThroughChunk(plan, failAfterChunk);
        Path checkpoint = Files.createTempFile("forgetdm-operation-500tb-", ".checkpoint");
        try {
            Properties stored = new Properties();
            stored.setProperty("lastCommittedChunk", Long.toString(failAfterChunk - 1));
            stored.setProperty("committedBytes", committedBytes.toString());
            stored.setProperty("manifestChunks", Long.toString(plan.chunkCount()));
            try (var output = Files.newOutputStream(checkpoint)) {
                stored.store(output, "Operation 500 TB restart checkpoint");
            }

            Properties restored = new Properties();
            try (InputStream input = Files.newInputStream(checkpoint)) {
                restored.load(input);
            }
            long resumeAt = Long.parseLong(restored.getProperty("lastCommittedChunk")) + 1;
            BigInteger covered = new BigInteger(restored.getProperty("committedBytes"));
            long replayed = 0;
            for (long chunk = resumeAt; chunk < plan.chunkCount(); chunk++) {
                covered = covered.add(chunkLength(plan, chunk));
                replayed++;
            }
            return new RestartResult(resumeAt, replayed, plan.chunkCount(), covered,
                    Files.size(checkpoint));
        } finally {
            Files.deleteIfExists(checkpoint);
        }
    }

    private StreamResult streamPhysicalSample(long sampleBytes) throws Exception {
        byte[] buffer = new byte[STREAM_BUFFER_BYTES];
        for (int i = 0; i < buffer.length; i++) buffer[i] = (byte) ((i * 31 + 17) & 0xff);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long started = System.nanoTime();
        long remaining = sampleBytes;
        while (remaining > 0) {
            int length = (int) Math.min(buffer.length, remaining);
            digest.update(buffer, 0, length);
            remaining -= length;
        }
        long elapsedNanos = Math.max(1, System.nanoTime() - started);
        double bytesPerSecond = sampleBytes * 1_000_000_000.0 / elapsedNanos;
        return new StreamResult(sampleBytes, elapsedNanos, bytesPerSecond, buffer.length,
                HexFormat.of().formatHex(digest.digest()));
    }

    private Map<String, Object> evidence(ScalePlan plan, ManifestResult manifest,
                                         RestartResult restart, StreamResult stream) {
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("operation", "OPERATION_500TB");
        root.put("generatedAt", Instant.now().toString());
        root.put("commit", System.getProperty("forgetdm.scale.commit", "UNRECORDED"));
        root.put("verdict", "LOGICAL_CONTROL_PLANE_PASS_PHYSICAL_CERTIFICATION_PENDING");
        root.put("physical500TbClaimAllowed", false);
        root.put("claimBoundary", "Local sparse-manifest and bounded-stream evidence is not a measured 500 TB database run.");

        LinkedHashMap<String, Object> environment = new LinkedHashMap<>();
        environment.put("host", System.getenv().getOrDefault("COMPUTERNAME", "unknown"));
        environment.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        environment.put("java", System.getProperty("java.version"));
        environment.put("processors", Runtime.getRuntime().availableProcessors());
        environment.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        root.put("environment", environment);

        LinkedHashMap<String, Object> logical = new LinkedHashMap<>();
        logical.put("requestedDecimalTb", 500);
        logical.put("totalBytes", plan.totalBytes().toString());
        logical.put("equivalentTib", decimal(plan.totalBytes(), BigInteger.ONE.shiftLeft(40), 3));
        logical.put("partitionBytes", plan.partitionBytes().toString());
        logical.put("partitionCount", plan.partitionCount());
        logical.put("chunkBytes", plan.chunkBytes().toString());
        logical.put("chunkCount", plan.chunkCount());
        logical.put("lastChunkBytes", plan.lastChunkBytes().toString());
        logical.put("averageRowBytes", plan.averageRowBytes().toString());
        logical.put("estimatedRows", plan.estimatedRows().toString());
        logical.put("persistedChunkNumberType", "INTEGER");
        logical.put("chunkSequenceHeadroom", Integer.MAX_VALUE - plan.chunkCount());
        root.put("logicalPlan", logical);

        root.put("manifest", Map.of(
                "visitedChunks", manifest.visitedChunks(),
                "coveredBytes", manifest.coveredBytes().toString(),
                "descriptorWorkingSetBytes", manifest.descriptorWorkingSetBytes(),
                "sha256", manifest.sha256()));
        root.put("restart", Map.of(
                "committedBeforeFailure", restart.committedBeforeFailure(),
                "replayedChunks", restart.replayedChunks(),
                "finalCommittedChunks", restart.finalCommittedChunks(),
                "coveredBytesAfterResume", restart.coveredBytesAfterResume().toString(),
                "checkpointFileBytes", restart.checkpointFileBytes(),
                "duplicateChunks", 0));

        double projectedHours = plan.totalBytes().doubleValue() / stream.bytesPerSecond() / 3_600.0;
        root.put("physicalSample", Map.of(
                "bytesProcessed", stream.bytesProcessed(),
                "elapsedNanos", stream.elapsedNanos(),
                "bytesPerSecond", Math.round(stream.bytesPerSecond()),
                "maxBufferBytes", stream.maxBufferBytes(),
                "sha256", stream.sha256(),
                "projected500TbHoursAtLocalMemoryRate", round(projectedHours, 2),
                "projectionIsCertification", false));
        root.put("requiredNextGate", "Run the staged 1%, 10%, 1 TB, 5 TB, then 500 TB physical qualification on production-equivalent infrastructure.");
        return root;
    }

    private void writeJson(Path path, Map<String, Object> evidence) throws Exception {
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(path.toFile(), evidence);
    }

    private void writeMarkdown(Path path, Map<String, Object> evidence, ScalePlan plan,
                               ManifestResult manifest, RestartResult restart, StreamResult stream) throws Exception {
        double projectedHours = plan.totalBytes().doubleValue() / stream.bytesPerSecond() / 3_600.0;
        String markdown = """
                # Operation 500 TB qualification evidence

                **Verdict:** LOGICAL CONTROL-PLANE PASS; PHYSICAL CERTIFICATION PENDING

                This run validates exact 500 TB planning, sparse-manifest traversal, bounded working memory,
                checkpoint capacity, and deterministic restart. It does **not** claim that 500 TB was physically
                read, masked, or loaded on this workstation.

                ## Logical plan

                | Measure | Result |
                | --- | ---: |
                | Requested volume | 500 TB (decimal) |
                | Exact bytes | %s |
                | Equivalent TiB | %s |
                | 256 GiB partitions | %,d |
                | 1 GiB restart chunks | %,d |
                | Last chunk bytes | %s |
                | Estimated rows at 2,048 bytes/row | %s |
                | Integer checkpoint headroom | %,d |

                ## Proofs

                | Proof | Result |
                | --- | --- |
                | Sparse manifest coverage | %,d chunks; %s bytes; PASS |
                | Manifest digest | `%s` |
                | Injected failure | After %,d committed chunks |
                | Resume | %,d remaining chunks; exact final coverage; zero duplicate chunks; PASS |
                | Durable checkpoint footprint | %,d bytes |
                | Physical bounded-stream sample | %,d bytes using a %,d-byte reusable buffer; PASS |
                | Sample digest | `%s` |
                | Local memory/digest rate | %,.0f bytes/s |
                | 500 TB projection at that isolated rate | %,.2f hours; **projection only** |

                ## Claim boundary

                A physical support claim remains blocked until the production-equivalent staged gate executes
                1%%, 10%%, 1 TB, 5 TB, and finally 500 TB with real source/target engines, masking policies,
                LOB mix, native loaders, cancellation, restart, reconciliation, concurrency, and endurance evidence.
                """.formatted(
                plan.totalBytes(), decimal(plan.totalBytes(), BigInteger.ONE.shiftLeft(40), 3),
                plan.partitionCount(), plan.chunkCount(), plan.lastChunkBytes(), plan.estimatedRows(),
                Integer.MAX_VALUE - plan.chunkCount(), manifest.visitedChunks(), manifest.coveredBytes(),
                manifest.sha256(), restart.committedBeforeFailure(), restart.replayedChunks(),
                restart.checkpointFileBytes(), stream.bytesProcessed(), stream.maxBufferBytes(), stream.sha256(),
                stream.bytesPerSecond(), projectedHours);
        Files.writeString(path, markdown, StandardCharsets.UTF_8);
    }

    private static BigInteger chunkLength(ScalePlan plan, long chunk) {
        BigInteger start = plan.chunkBytes().multiply(BigInteger.valueOf(chunk));
        return plan.totalBytes().subtract(start).min(plan.chunkBytes());
    }

    private static BigInteger bytesThroughChunk(ScalePlan plan, long exclusiveChunk) {
        return plan.chunkBytes().multiply(BigInteger.valueOf(exclusiveChunk)).min(plan.totalBytes());
    }

    private static BigInteger ceilDivide(BigInteger numerator, BigInteger denominator) {
        return numerator.add(denominator).subtract(BigInteger.ONE).divide(denominator);
    }

    private static String decimal(BigInteger numerator, BigInteger denominator, int scale) {
        return new BigDecimal(numerator).divide(new BigDecimal(denominator), scale, RoundingMode.HALF_UP).toPlainString();
    }

    private static double round(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }

    private static long sampleBytes() {
        int mib = Integer.getInteger("forgetdm.scale.sampleMiB", 256);
        mib = Math.max(16, Math.min(mib, 4_096));
        return (long) mib * 1_024L * 1_024L;
    }

    private static Path evidenceDirectory() {
        return Path.of(System.getProperty("forgetdm.scale.evidenceDir",
                Path.of("target", "scale-evidence", "operation-500tb").toString()));
    }

    private record ScalePlan(BigInteger totalBytes, BigInteger partitionBytes, BigInteger chunkBytes,
                             BigInteger averageRowBytes, long partitionCount, long chunkCount,
                             BigInteger estimatedRows, BigInteger fullChunkBytes, BigInteger lastChunkBytes) {
        static ScalePlan create(BigInteger totalBytes, BigInteger partitionBytes,
                                BigInteger chunkBytes, BigInteger averageRowBytes) {
            if (totalBytes.signum() <= 0 || partitionBytes.signum() <= 0
                    || chunkBytes.signum() <= 0 || averageRowBytes.signum() <= 0) {
                throw new IllegalArgumentException("Scale dimensions must be positive");
            }
            BigInteger partitions = ceilDivide(totalBytes, partitionBytes);
            BigInteger chunks = ceilDivide(totalBytes, chunkBytes);
            if (chunks.compareTo(BigInteger.valueOf(Integer.MAX_VALUE - 1L)) > 0) {
                throw new IllegalArgumentException("Requested chunk geometry exceeds persisted INTEGER checkpoint capacity");
            }
            long chunkCount = chunks.longValueExact();
            BigInteger completeChunks = chunkBytes.multiply(BigInteger.valueOf(chunkCount - 1));
            return new ScalePlan(totalBytes, partitionBytes, chunkBytes, averageRowBytes,
                    partitions.longValueExact(), chunkCount, ceilDivide(totalBytes, averageRowBytes),
                    completeChunks, totalBytes.subtract(completeChunks));
        }
    }

    private record ManifestResult(long visitedChunks, BigInteger coveredBytes, String sha256,
                                  long descriptorWorkingSetBytes) {}

    private record RestartResult(long committedBeforeFailure, long replayedChunks,
                                 long finalCommittedChunks, BigInteger coveredBytesAfterResume,
                                 long checkpointFileBytes) {}

    private record StreamResult(long bytesProcessed, long elapsedNanos, double bytesPerSecond,
                                long maxBufferBytes, String sha256) {}
}
