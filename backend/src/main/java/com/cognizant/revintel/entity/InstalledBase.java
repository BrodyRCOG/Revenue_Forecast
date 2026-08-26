package com.cognizant.revintel.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A CMDB-style asset record: one client's deployment of one {@link OemModel} at one site. */
@Entity
@Table(name = "installed_base")
public class InstalledBase {

    @Id
    @Column(name = "id", length = 32)
    private String id;

    @Column(name = "client_id", nullable = false, length = 32)
    private String clientId;

    @Column(name = "oem_model_id", nullable = false, length = 32)
    private String oemModelId;

    @Column(name = "asset_tag", length = 64)
    private String assetTag;

    @Column(name = "site", length = 128)
    private String site;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "install_date")
    private LocalDate installDate;

    /** Mission Critical | Business Critical | Standard */
    @Column(name = "criticality", length = 32)
    private String criticality;

    @Column(name = "annual_support_cost_usd", precision = 18, scale = 2)
    private BigDecimal annualSupportCostUsd;

    protected InstalledBase() {
    }

    public String getId() {
        return id;
    }

    public String getClientId() {
        return clientId;
    }

    public String getOemModelId() {
        return oemModelId;
    }

    public String getAssetTag() {
        return assetTag;
    }

    public String getSite() {
        return site;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public LocalDate getInstallDate() {
        return installDate;
    }

    public String getCriticality() {
        return criticality;
    }

    public BigDecimal getAnnualSupportCostUsd() {
        return annualSupportCostUsd;
    }
}
