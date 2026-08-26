package com.cognizant.revintel.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A CRM opportunity. Open rows are live pipeline; Won/Lost rows are the closed history the
 * Bayesian win-rate smoothing is fitted on.
 *
 * <p>Note the deliberate distinction between {@link #getProbability()} -- the rep-entered CRM
 * number -- and the win rate {@code ForecastingService} derives from closed history. Weighted
 * pipeline is computed from the derived rate, never from this column: weighting off the raw
 * probability is what made the coverage ratio ignore what-if overrides in the reference build.
 */
@Entity
@Table(name = "opportunities")
public class Opportunity {

    @Id
    @Column(name = "id", length = 32)
    private String id;

    @Column(name = "client_id", nullable = false, length = 32)
    private String clientId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    /** Infrastructure Refresh | Modernization | Managed Services | Security | Cloud Migration */
    @Column(name = "opportunity_type", nullable = false, length = 64)
    private String opportunityType;

    @Column(name = "practice", nullable = false, length = 64)
    private String practice;

    @Column(name = "resource_group", length = 64)
    private String resourceGroup;

    @Column(name = "amount_usd", nullable = false, precision = 18, scale = 2)
    private BigDecimal amountUsd;

    /** Rep-entered CRM probability. Informational only -- see class javadoc. */
    @Column(name = "probability", precision = 6, scale = 4)
    private BigDecimal probability;

    @Column(name = "stage", length = 48)
    private String stage;

    /** Open | Won | Lost */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** CRM | Referral | Partner | Renewal */
    @Column(name = "lead_source", length = 48)
    private String leadSource;

    @Column(name = "created_date")
    private LocalDate createdDate;

    @Column(name = "close_date")
    private LocalDate closeDate;

    /** Fiscal quarter of {@code closeDate}, e.g. {@code 2026-Q3}. Lexicographically sortable. */
    @Column(name = "quarter", nullable = false, length = 16)
    private String quarter;

    protected Opportunity() {
    }

    public String getId() {
        return id;
    }

    public String getClientId() {
        return clientId;
    }

    public String getName() {
        return name;
    }

    public String getOpportunityType() {
        return opportunityType;
    }

    public String getPractice() {
        return practice;
    }

    public String getResourceGroup() {
        return resourceGroup;
    }

    public BigDecimal getAmountUsd() {
        return amountUsd;
    }

    public BigDecimal getProbability() {
        return probability;
    }

    public String getStage() {
        return stage;
    }

    public String getStatus() {
        return status;
    }

    public String getLeadSource() {
        return leadSource;
    }

    public LocalDate getCreatedDate() {
        return createdDate;
    }

    public LocalDate getCloseDate() {
        return closeDate;
    }

    public String getQuarter() {
        return quarter;
    }
}
