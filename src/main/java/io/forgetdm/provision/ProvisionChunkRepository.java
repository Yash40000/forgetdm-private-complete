package io.forgetdm.provision;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface ProvisionChunkRepository extends JpaRepository<ProvisionChunkEntity, Long> {
    List<ProvisionChunkEntity> findByJobIdOrderByTableNameAscChunkNoAsc(Long jobId);
    List<ProvisionChunkEntity> findByJobIdAndTableNameIgnoreCaseOrderByChunkNoAsc(Long jobId, String tableName);
    Optional<ProvisionChunkEntity> findFirstByJobIdAndTableNameIgnoreCaseAndStateOrderByChunkNoDesc(
            Long jobId, String tableName, String state);
    long countByJobIdAndState(Long jobId, String state);
    @Transactional void deleteByJobId(Long jobId);
}
