package com.daou.erdstudio.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** ERD 도메인(테이블 그룹). key 는 방 안에서만 유일하므로 PK 는 대체키(id)를 사용한다. */
@Entity
@Table(name = "erd_domain", uniqueConstraints = @UniqueConstraint(columnNames = {"room_id", "key"}))
public class ErdDomain {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_id", nullable = false)
    private Long roomId;

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

    public ErdDomain(Long roomId, String key, String name, String color, int sortOrder) {
        this.roomId = roomId;
        this.key = key;
        this.name = name;
        this.color = color;
        this.sortOrder = sortOrder;
    }

    /** 이름·색상·정렬 순서를 바꾼다. key 는 테이블이 참조하므로 변경하지 않는다. */
    public void update(String name, String color, int sortOrder) {
        this.name = name;
        this.color = color;
        this.sortOrder = sortOrder;
    }

    public Long getId() {
        return id;
    }

    public Long getRoomId() {
        return roomId;
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
