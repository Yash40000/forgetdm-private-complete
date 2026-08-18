package io.forgetdm.mainframe;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DataScopeMainframeFieldMappingRepository
        extends JpaRepository<DataScopeMainframeFieldMappingEntity, Long> {
    List<DataScopeMainframeFieldMappingEntity> findByAssetIdAndPolicyIdOrderByOrdinalNoAscIdAsc(
            Long assetId, Long policyId);
    List<DataScopeMainframeFieldMappingEntity> findByAssetIdOrderByPolicyIdAscOrdinalNoAscIdAsc(Long assetId);
    void deleteByAssetIdAndPolicyId(Long assetId, Long policyId);
}
