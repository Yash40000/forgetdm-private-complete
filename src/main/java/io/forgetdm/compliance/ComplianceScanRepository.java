package io.forgetdm.compliance;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ComplianceScanRepository extends JpaRepository<ComplianceScanEntity, Long> {
    List<ComplianceScanEntity> findAllByOrderByStartedAtDesc(Pageable page);
    List<ComplianceScanEntity> findByScanTypeOrderByStartedAtDesc(String scanType, Pageable page);
    List<ComplianceScanEntity> findByTargetDataSourceIdOrderByStartedAtDesc(Long targetDataSourceId, Pageable page);
}
