package com.daou.erdstudio.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** 변경 이력 — op 원문과 적용 직후 전체 스키마 스냅샷(JSON)을 보관한다. */
@Entity
@Table(name = "erd_history")
public class ErdHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_name", nullable = false)
    private String userName;

    @Column(name = "op_kind", nullable = false)
    private String opKind;

    @Column(nullable = false)
    private String target;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String op;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String snapshot;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ErdHistory() {
    }

    public ErdHistory(String userName, String opKind, String target, String op, String snapshot) {
        this.userName = userName;
        this.opKind = opKind;
        this.target = target;
        this.op = op;
        this.snapshot = snapshot;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getUserName() {
        return userName;
    }

    public String getOpKind() {
        return opKind;
    }

    public String getTarget() {
        return target;
    }

    public String getOp() {
        return op;
    }

    public String getSnapshot() {
        return snapshot;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
