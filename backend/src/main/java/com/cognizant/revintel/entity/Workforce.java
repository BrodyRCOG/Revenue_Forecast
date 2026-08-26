package com.cognizant.revintel.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * Headcount and utilisation for one delivery resource group.
 *
 * <p>The generator sizes {@code headcount} off the <em>same</em> historical quarterly demand-hours
 * model the capacity math reads, so hiring recommendations stay in a believable range. Sizing this
 * table independently of revenue scale is what produced the "hire 5x your bench" bug.
 */
@Entity
@Table(name = "workforce")
public class Workforce {

    @Id
    @Column(name = "id", length = 32)
    private String id;

    @Column(name = "resource_group", nullable = false, length = 64)
    private String resourceGroup;

    @Column(name = "practice", nullable = false, length = 64)
    private String practice;

    @Column(name = "region", length = 64)
    private String region;

    @Column(name = "headcount", nullable = false)
    private Integer headcount;

    @Column(name = "contractor_headcount", nullable = false)
    private Integer contractorHeadcount;

    @Column(name = "avg_bill_rate_usd", nullable = false, precision = 10, scale = 2)
    private BigDecimal avgBillRateUsd;

    @Column(name = "target_utilization", nullable = false, precision = 6, scale = 4)
    private BigDecimal targetUtilization;

    @Column(name = "current_utilization", nullable = false, precision = 6, scale = 4)
    private BigDecimal currentUtilization;

    /** Billable-capable hours per FTE per year before utilisation is applied. */
    @Column(name = "annual_capacity_hours_per_fte", nullable = false, precision = 10, scale = 2)
    private BigDecimal annualCapacityHoursPerFte;

    protected Workforce() {
    }

    public String getId() {
        return id;
    }

    public String getResourceGroup() {
        return resourceGroup;
    }

    public String getPractice() {
        return practice;
    }

    public String getRegion() {
        return region;
    }

    public Integer getHeadcount() {
        return headcount;
    }

    public Integer getContractorHeadcount() {
        return contractorHeadcount;
    }

    public BigDecimal getAvgBillRateUsd() {
        return avgBillRateUsd;
    }

    public BigDecimal getTargetUtilization() {
        return targetUtilization;
    }

    public BigDecimal getCurrentUtilization() {
        return currentUtilization;
    }

    public BigDecimal getAnnualCapacityHoursPerFte() {
        return annualCapacityHoursPerFte;
    }
}
