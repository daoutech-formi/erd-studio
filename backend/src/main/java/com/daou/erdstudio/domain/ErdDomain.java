package com.daou.erdstudio.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** ERD 도메인(테이블 그룹) — 기존 erd_domain 테이블과 동일 매핑. */
@Entity
@Table(name = "erd_domain")
public class ErdDomain {

    @Id
    @Column(name = "key")
    private String key;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String color;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected ErdDomain() {
    }

    public ErdDomain(String key, String name, String color, int sortOrder) {
        this.key = key;
        this.name = name;
        this.color = color;
        this.sortOrder = sortOrder;
    }

    public String getKey() {
        return key;
    }

    public String getName() {
        return name;
    }

    public String getColor() {
        return color;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}
