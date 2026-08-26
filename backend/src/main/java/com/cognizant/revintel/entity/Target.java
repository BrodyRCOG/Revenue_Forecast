package com.cognizant.revintel.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/** Quarterly revenue target for one practice. Denominator of the pipeline coverage ratio. */
@Entity
@Table(name = "targets")
public class Target {

    @Id
    @Column(name = "id", length = 32)
    private String id;

    @Column(name = "practice", nullable = false, length = 64)
    private String practice;

    @Column(name = "quarter", nullable = false, length = 16)
    private String quarter;

    @Column(name = "target_amount_usd", nullable = false, precision = 18, scale = 2)
    private BigDecimal targetAmountUsd;

    protected Target() {
    }

    public String getId() {
        return id;
    }

    public String getPractice() {
        return practice;
    }

    public String getQuarter() {
        return quarter;
    }

    public BigDecimal getTargetAmountUsd() {
        return targetAmountUsd;
    }
}
