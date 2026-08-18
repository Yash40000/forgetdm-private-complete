package io.forgetdm.compliance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface ComplianceFindingRepository extends JpaRepository<ComplianceFindingEntity, Long> {
    List<ComplianceFindingEntity> findByScanIdOrderBySeverityAscIdAsc(Long scanId);

    /**
     * Derived delete queries need their own transaction. The FK is also declared
     * {@code ON DELETE CASCADE}, so this is belt-and-braces for the JPA path.
     */
    @Transactional
    void deleteByScanId(Long scanId);

    long countByScanIdAndSeverity(Long scanId, String severity);
}
