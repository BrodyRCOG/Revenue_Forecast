package com.cognizant.revintel.repository;

import com.cognizant.revintel.entity.InstalledBase;
import com.cognizant.revintel.repository.projection.AssetLifecycleRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface InstalledBaseRepository extends JpaRepository<InstalledBase, String> {

    /**
     * The one place asset lifecycle data is joined. Signal detection needs asset + OEM dates +
     * client attributes together; doing the join here keeps SQL out of the service layer.
     */
    @Query("""
            select new com.cognizant.revintel.repository.projection.AssetLifecycleRow(
                ib.id, c.id, c.name, c.segment, c.revenueTier, ib.site, ib.quantity,
                ib.criticality, ib.annualSupportCostUsd,
                m.id, m.oem, m.modelName, m.category, m.endOfSale, m.endOfSupport,
                m.realAnchor, m.sourceUrl, m.sourceConfidence)
            from InstalledBase ib, OemModel m, Client c
            where ib.oemModelId = m.id and ib.clientId = c.id
            """)
    List<AssetLifecycleRow> withLifecycle();
}
