package com.daou.erdstudio.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 테이블 간 참조 관계 (child → parent) — 기존 erd_relation 테이블과 동일 매핑. */
@Entity
@Table(name = "erd_relation")
public class ErdRelation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "child_table_id", nullable = false)
    private Long childTableId;

    @Column(name = "parent_table_id", nullable = false)
    private Long parentTableId;

    @Column(nullable = false)
    private String label;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected ErdRelation() {
    }

    public ErdRelation(Long childTableId, Long parentTableId, String label, int sortOrder) {
        this.childTableId = childTableId;
        this.parentTableId = parentTableId;
        this.label = label;
        this.sortOrder = sortOrder;
    }

    public Long getChildTableId() {
        return childTableId;
    }

    public Long getParentTableId() {
        return parentTableId;
    }

    public String getLabel() {
        return label;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}
