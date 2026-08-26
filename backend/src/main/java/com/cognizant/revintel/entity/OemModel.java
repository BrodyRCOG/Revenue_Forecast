package com.cognizant.revintel.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

/**
 * An OEM product model with EOL/EOS lifecycle dates.
 *
 * <p>Seven rows are <em>real</em>, sourced anchors ({@code isRealAnchor = true}, with a live
 * {@code sourceUrl}); the remaining rows are procedural variants derived from those anchors'
 * lifecycle spans and carry {@code sourceConfidence = "synthetic"}. Downstream code and the eval
 * suite must always be able to tell the two apart -- never overwrite these three fields.
 */
@Entity
@Table(name = "oem_models")
public class OemModel {

    @Id
    @Column(name = "id", length = 32)
    private String id;

    @Column(name = "oem", nullable = false, length = 64)
    private String oem;

    @Column(name = "model_name", nullable = false, length = 160)
    private String modelName;

    /** Switching | Server | Firewall | Storage | Server OS | Database | Hypervisor | ... */
    @Column(name = "category", nullable = false, length = 64)
    private String category;

    @Column(name = "lifecycle_years")
    private Integer lifecycleYears;

    @Column(name = "support_tail_years")
    private Integer supportTailYears;

    @Column(name = "release_date")
    private LocalDate releaseDate;

    @Column(name = "end_of_sale")
    private LocalDate endOfSale;

    @Column(name = "end_of_support")
    private LocalDate endOfSupport;

    @Column(name = "is_real_anchor", nullable = false)
    private boolean realAnchor;

    @Column(name = "source_url", length = 512)
    private String sourceUrl;

    /** vendor_official | aggregator | uncertain | synthetic */
    @Column(name = "source_confidence", nullable = false, length = 32)
    private String sourceConfidence;

    protected OemModel() {
    }

    public String getId() {
        return id;
    }

    public String getOem() {
        return oem;
    }

    public String getModelName() {
        return modelName;
    }

    public String getCategory() {
        return category;
    }

    public Integer getLifecycleYears() {
        return lifecycleYears;
    }

    public Integer getSupportTailYears() {
        return supportTailYears;
    }

    public LocalDate getReleaseDate() {
        return releaseDate;
    }

    public LocalDate getEndOfSale() {
        return endOfSale;
    }

    public LocalDate getEndOfSupport() {
        return endOfSupport;
    }

    public boolean isRealAnchor() {
        return realAnchor;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public String getSourceConfidence() {
        return sourceConfidence;
    }
}
