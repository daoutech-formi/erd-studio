package com.daou.erdstudio.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** ERD 테이블 노드 — 기존 erd_table 테이블과 동일 매핑 + 위치 좌표(pos_x/pos_y) 추가. */
@Entity
@Table(name = "erd_table")
public class ErdTable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(name = "domain_key", nullable = false)
    private String domainKey;

    @Column(nullable = false)
    private String description;

    @Column(name = "is_hub", nullable = false)
    private boolean hub;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /** 사용자가 드래그로 지정한 좌표. NULL이면 프론트가 자동 배치한다. */
    @Column(name = "pos_x")
    private Double posX;

    @Column(name = "pos_y")
    private Double posY;

    protected ErdTable() {
    }

    public ErdTable(String name, String domainKey, String description, boolean hub,
                    int sortOrder, Double posX, Double posY) {
        this.name = name;
        this.domainKey = domainKey;
        this.description = description;
        this.hub = hub;
        this.sortOrder = sortOrder;
        this.posX = posX;
        this.posY = posY;
    }

    public void rename(String newName) {
        this.name = newName;
    }

    public void update(String domainKey, String description, boolean hub) {
        this.domainKey = domainKey;
        this.description = description;
        this.hub = hub;
    }

    public void moveTo(Double x, Double y) {
        this.posX = x;
        this.posY = y;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDomainKey() {
        return domainKey;
    }

    public String getDescription() {
        return description;
    }

    public boolean isHub() {
        return hub;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public Double getPosX() {
        return posX;
    }

    public Double getPosY() {
        return posY;
    }
}
