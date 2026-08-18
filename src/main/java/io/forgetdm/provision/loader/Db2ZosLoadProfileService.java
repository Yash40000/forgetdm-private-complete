package io.forgetdm.provision.loader;

import io.forgetdm.common.ApiException;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.mainframe.MainframeConnectionEntity;
import io.forgetdm.mainframe.MainframeConnectionRepository;
import io.forgetdm.security.OwnershipGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class Db2ZosLoadProfileService {
    private static final Pattern QUALIFIER = Pattern.compile("[A-Z#$@][A-Z0-9#$@-]{0,7}");
    private static final Pattern HLQ = Pattern.compile("[A-Z#$@][A-Z0-9#$@-]{0,7}(\\.[A-Z#$@][A-Z0-9#$@-]{0,7}){0,2}");
    private static final Pattern JOB_ACCOUNTING = Pattern.compile("[A-Z0-9#$@.,()' /-]{1,64}");

    private final Db2ZosLoadProfileRepository profiles;
    private final DataSourceService dataSources;
    private final MainframeConnectionRepository connections;
    private final OwnershipGuard ownership;

    public Db2ZosLoadProfileService(Db2ZosLoadProfileRepository profiles, DataSourceService dataSources,
                                    MainframeConnectionRepository connections, OwnershipGuard ownership) {
        this.profiles = profiles;
        this.dataSources = dataSources;
        this.connections = connections;
        this.ownership = ownership;
    }

    public Optional<Db2ZosLoadProfileEntity> find(Long dataSourceId) {
        if (dataSourceId == null) return Optional.empty();
        dataSources.get(dataSourceId);
        return profiles.findByDataSourceId(dataSourceId).map(profile -> {
            visibleConnection(profile.getMainframeConnectionId());
            return profile;
        });
    }

    public List<Db2ZosLoadProfileEntity> list() {
        Set<Long> visibleSources = dataSources.list().stream().map(DataSourceEntity::getId)
                .collect(java.util.stream.Collectors.toSet());
        return profiles.findAll().stream()
                .filter(profile -> visibleSources.contains(profile.getDataSourceId()))
                .filter(profile -> connections.findById(profile.getMainframeConnectionId())
                        .filter(this::canSeeConnection).isPresent())
                .toList();
    }

    public long configuredCount() { return list().size(); }

    public ResolvedProfile resolve(Long dataSourceId) {
        Db2ZosLoadProfileEntity profile = find(dataSourceId)
                .orElseThrow(() -> ApiException.bad("Db2 z/OS native LOAD profile is not configured for data source " + dataSourceId));
        MainframeConnectionEntity connection = visibleConnection(profile.getMainframeConnectionId());
        if (!"ZOWE".equalsIgnoreCase(connection.getType())) {
            throw ApiException.bad("Db2 z/OS native LOAD requires a ZOWE/z/OSMF connection");
        }
        return new ResolvedProfile(profile, connection);
    }

    @Transactional
    public Db2ZosLoadProfileEntity save(Long dataSourceId, Db2ZosLoadProfileEntity input) {
        DataSourceEntity source = dataSources.get(dataSourceId);
        if (!"DB2ZOS".equals(NativeLoadRegistry.engineOf(source))) {
            throw ApiException.bad("A Db2 z/OS LOAD profile can only be assigned to a DB2ZOS data source");
        }
        if (input == null || input.getMainframeConnectionId() == null) {
            throw ApiException.bad("Select a ZOWE/z/OSMF connection");
        }
        MainframeConnectionEntity connection = visibleConnection(input.getMainframeConnectionId());
        if (!"ZOWE".equalsIgnoreCase(connection.getType())) {
            throw ApiException.bad("Selected connection is not a ZOWE/z/OSMF connection");
        }
        Db2ZosLoadProfileEntity saved = profiles.findByDataSourceId(dataSourceId)
                .orElseGet(Db2ZosLoadProfileEntity::new);
        saved.setDataSourceId(dataSourceId);
        saved.setMainframeConnectionId(input.getMainframeConnectionId());
        saved.setSubsystem(requiredQualifier(input.getSubsystem(), "Db2 subsystem"));
        saved.setWorkHlq(requiredHlq(input.getWorkHlq()));
        saved.setProcedureName(optionalQualifier(input.getProcedureName(), "DSNUPROC", "Procedure name"));
        saved.setJobClass(one(input.getJobClass(), "A", "Job class"));
        saved.setMessageClass(one(input.getMessageClass(), "X", "Message class"));
        saved.setJobAccounting(jobAccounting(input.getJobAccounting()));
        saved.setWorkUnit(optionalQualifier(input.getWorkUnit(), "SYSDA", "Work unit"));
        String logging = upper(input.getLoggingMode(), "RECOVERABLE");
        if (!List.of("RECOVERABLE", "MINIMAL_LOGGING").contains(logging)) {
            throw ApiException.bad("Logging mode must be RECOVERABLE or MINIMAL_LOGGING");
        }
        saved.setLoggingMode(logging);
        if (input.getMaxReturnCode() < 0 || input.getMaxReturnCode() > 4) {
            throw ApiException.bad("Maximum accepted return code must be between 0 and 4");
        }
        saved.setMaxReturnCode(input.getMaxReturnCode());
        saved.setPollSeconds(between(input.getPollSeconds(), 2, 60, 5, "Poll seconds"));
        saved.setTimeoutSeconds(between(input.getTimeoutSeconds(), 60, 86400, 3600, "Timeout seconds"));
        saved.setCleanupRemote(input.isCleanupRemote());
        saved.setUpdatedAt(Instant.now());
        return profiles.save(saved);
    }

    @Transactional
    public void delete(Long dataSourceId) {
        dataSources.get(dataSourceId);
        profiles.findByDataSourceId(dataSourceId).ifPresent(profiles::delete);
    }

    private MainframeConnectionEntity visibleConnection(Long id) {
        MainframeConnectionEntity connection = connections.findById(id)
                .orElseThrow(() -> ApiException.bad("The z/OSMF connection assigned to this loader profile no longer exists"));
        ownership.assertCanSee("mainframe connection", id, connection.getOwnerUserId(),
                connection.getOwnerGroupId(), connection.getVisibility());
        return connection;
    }

    private boolean canSeeConnection(MainframeConnectionEntity connection) {
        return ownership.canSee(connection.getOwnerUserId(), connection.getOwnerGroupId(), connection.getVisibility());
    }

    private String jobAccounting(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!JOB_ACCOUNTING.matcher(normalized).matches()) {
            throw ApiException.bad("JES accounting information contains unsupported job-card characters");
        }
        return normalized;
    }

    private String requiredQualifier(String value, String label) {
        String normalized = upper(value, "");
        if (!QUALIFIER.matcher(normalized).matches()) throw ApiException.bad(label + " is not a valid z/OS qualifier");
        return normalized;
    }

    private String optionalQualifier(String value, String fallback, String label) {
        String normalized = upper(value, fallback);
        if (!QUALIFIER.matcher(normalized).matches()) throw ApiException.bad(label + " is not a valid z/OS qualifier");
        return normalized;
    }

    private String requiredHlq(String value) {
        String normalized = upper(value, "");
        if (!HLQ.matcher(normalized).matches() || normalized.length() > 26) {
            throw ApiException.bad("Work HLQ must contain one to three valid z/OS qualifiers and leave room for generated data set names");
        }
        return normalized;
    }

    private String one(String value, String fallback, String label) {
        String normalized = upper(value, fallback);
        if (normalized.length() != 1 || !normalized.matches("[A-Z0-9]")) throw ApiException.bad(label + " must be one character");
        return normalized;
    }

    private int between(int value, int min, int max, int fallback, String label) {
        int normalized = value <= 0 ? fallback : value;
        if (normalized < min || normalized > max) throw ApiException.bad(label + " must be between " + min + " and " + max);
        return normalized;
    }

    private String upper(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT);
    }

    public record ResolvedProfile(Db2ZosLoadProfileEntity profile, MainframeConnectionEntity connection) { }
}
