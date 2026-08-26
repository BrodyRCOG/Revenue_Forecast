package com.cognizant.revintel.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * Delivered hours and billings for one project, resource group and quarter. This is the historical
 * demand record the capacity plausibility guard compares forecast-implied demand against.
 */
@Entity
@Table(name = "project_billing")
public class ProjectBilling {

    @Id
    @Column(name = "id", length = 32)
    private String id;

    @Column(name = "client_id", nullable = false, length = 32)
    private String clientId;

    @Column(name = "project_name", nullable = false, length = 200)
    private String projectName;

    @Column(name = "practice", nullable = false, length = 64)
    private String practice;

    @Column(name = "resource_group", nullable = false, length = 64)
    private String resourceGroup;

    @Column(name = "quarter", nullable = false, length = 16)
    private String quarter;

    @Column(name = "billed_hours", nullable = false, precision = 14, scale = 2)
    private BigDecimal billedHours;

    @Column(name = "billed_amount_usd", nullable = false, precision = 18, scale = 2)
    private BigDecimal billedAmountUsd;

    /** Realised delivery utilisation for this project-quarter, 0..1. */
    @Column(name = "delivery_utilization", precision = 6, scale = 4)
    private BigDecimal deliveryUtilization;

    protected ProjectBilling() {
    }

    public String getId() {
        return id;
    }

    public String getClientId() {
        return clientId;
    }

    public String getProjectName() {
        return projectName;
    }

    public String getPractice() {
        return practice;
    }

    public String getResourceGroup() {
        return resourceGroup;
    }

    public String getQuarter() {
        return quarter;
    }

    public BigDecimal getBilledHours() {
        return billedHours;
    }

    public BigDecimal getBilledAmountUsd() {
        return billedAmountUsd;
    }

    public BigDecimal getDeliveryUtilization() {
        return deliveryUtilization;
    }
}
