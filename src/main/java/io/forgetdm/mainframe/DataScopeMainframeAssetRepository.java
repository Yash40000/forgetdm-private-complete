package io.forgetdm.mainframe;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DataScopeMainframeAssetRepository extends JpaRepository<DataScopeMainframeAssetEntity, Long> {
    List<DataScopeMainframeAssetEntity> findByDatasetIdOrderByOrdinalNoAscIdAsc(Long datasetId);
    List<DataScopeMainframeAssetEntity> findByDatasetIdAndEnabledTrueOrderByOrdinalNoAscIdAsc(Long datasetId);
    Optional<DataScopeMainframeAssetEntity> findByDatasetIdAndLogicalRoleIgnoreCase(Long datasetId, String logicalRole);
    void deleteByDatasetId(Long datasetId);
}
