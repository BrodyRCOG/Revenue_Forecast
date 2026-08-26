package com.cognizant.revintel.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A managed-services contract with recurring ARR and a renewal-risk grade. */
@Entity
@Table(name = "contracts")
public class Contract {

    @Id
    @Column(name = "id", length = 32)
    private String id;

    @Column(name = "client_id", nullable = false, length = 32)
    private String clientId;

    /** Managed Network | Managed Security | Managed Cloud | Managed Endpoint | Hosting */
    @Column(name = "service_type", nullable = false, length = 64)
    private String serviceType;

    @Column(name = "arr_usd", nullable = false, precision = 18, scale = 2)
    private BigDecimal arrUsd;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    /** Low | Medium | High | Critical */
    @Column(name = "renewal_risk", nullable = false, length = 16)
    private String renewalRisk;

    @Column(name = "renewal_probability", precision = 6, scale = 4)
    private BigDecimal renewalProbability;

    @Column(name = "auto_renew", nullable = false)
    private boolean autoRenew;

    @Column(name = "nps_score")
    private Integer npsScore;

    protected Contract() {
    }

    public String getId() {
        return id;
    }

    public String getClientId() {
        return clientId;
    }

    public String getServiceType() {
        return serviceType;
    }

    public BigDecimal getArrUsd() {
        return arrUsd;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public String getRenewalRisk() {
        return renewalRisk;
    }

    public BigDecimal getRenewalProbability() {
        return renewalProbability;
    }

    public boolean isAutoRenew() {
        return autoRenew;
    }

    public Integer getNpsScore() {
        return npsScore;
    }
}
