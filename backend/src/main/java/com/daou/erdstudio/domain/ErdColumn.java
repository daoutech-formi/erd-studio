package com.daou.erdstudio.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 테이블의 컬럼 정의 — 기존 erd_column 테이블과 동일 매핑. */
@Entity
@Table(name = "erd_column")
public class ErdColumn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "table_id", nullable = false)
    private Long tableId;

    @Column(nullable = false)
    private String name;

    @Column(name = "col_type", nullable = false)
    private String colType;

    @Column(name = "comment", nullable = false)
    private String comment;

    /** PK/UK/FK 등 플래그 문자열 (없으면 빈 문자열). */
    @Column(nullable = false)
    private String flag;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected ErdColumn() {
    }

    public ErdColumn(Long tableId, String name, String colType, String comment, String flag, int sortOrder) {
        this.tableId = tableId;
        this.name = name;
        this.colType = colType;
        this.comment = comment;
        this.flag = flag;
        this.sortOrder = sortOrder;
    }

    public Long getTableId() {
        return tableId;
    }

    public String getName() {
        return name;
    }

    public String getColType() {
        return colType;
    }

    public String getComment() {
        return comment;
    }

    public String getFlag() {
        return flag;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}
