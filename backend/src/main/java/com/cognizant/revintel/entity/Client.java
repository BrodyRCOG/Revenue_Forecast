package com.cognizant.revintel.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A bank / credit-union client. Ids are assigned by the synthetic generator (CLI-0001...). */
@Entity
@Table(name = "clients")
public class Client {

    @Id
    @Column(name = "id", length = 32)
    private String id;

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    /** Community Bank | Credit Union | Regional Bank | De Novo/Digital Bank | Trust & Wealth Bank */
    @Column(name = "segment", nullable = false, length = 64)
    private String segment;

    @Column(name = "region", length = 64)
    private String region;

    @Column(name = "state", length = 8)
    private String state;

    /** Enterprise | Mid-Market | SMB -- drives installed-base sampling weight. */
    @Column(name = "revenue_tier", length = 32)
    private String revenueTier;

    @Column(name = "asset_size_usd", precision = 18, scale = 2)
    private BigDecimal assetSizeUsd;

    @Column(name = "branch_count")
    private Integer branchCount;

    @Column(name = "core_banking_platform", length = 64)
    private String coreBankingPlatform;

    @Column(name = "account_owner", length = 96)
    private String accountOwner;

    @Column(name = "relationship_start")
    private LocalDate relationshipStart;

    protected Client() {
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getSegment() {
        return segment;
    }

    public String getRegion() {
        return region;
    }

    public String getState() {
        return state;
    }

    public String getRevenueTier() {
        return revenueTier;
    }

    public BigDecimal getAssetSizeUsd() {
        return assetSizeUsd;
    }

    public Integer getBranchCount() {
        return branchCount;
    }

    public String getCoreBankingPlatform() {
        return coreBankingPlatform;
    }

    public String getAccountOwner() {
        return accountOwner;
    }

    public LocalDate getRelationshipStart() {
        return relationshipStart;
    }
}
