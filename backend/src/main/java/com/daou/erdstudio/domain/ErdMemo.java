package com.daou.erdstudio.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** 캔버스에 붙이는 스티키 메모. 식별자(memo_key)는 클라이언트가 만들고 방 안에서만 유일하다. */
@Entity
@Table(name = "erd_memo", uniqueConstraints = @UniqueConstraint(columnNames = {"room_id", "memo_key"}))
public class ErdMemo {

    public static final String DEFAULT_COLOR = "#ffd479";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Column(name = "memo_key", nullable = false, length = 64)
    private String memoKey;

    @Column(nullable = false, length = 1000)
    private String text;

    @Column(nullable = false, length = 32)
    private String color;

    @Column(name = "pos_x", nullable = false)
    private double posX;

    @Column(name = "pos_y", nullable = false)
    private double posY;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /** 사용자 지정 폭/높이. null이면 기본 폭·내용 높이(프론트가 계산)를 쓴다. */
    @Column(name = "memo_w")
    private Double width;

    @Column(name = "memo_h")
    private Double height;

    /** 연결된 테이블명 목록의 JSON 배열 문자열 (예: ["order","user"]). 없으면 "[]". */
    // default 필수 — 메모가 이미 있는 DB에서 ddl-auto update가 NOT NULL 컬럼을 추가하려면 기본값이 있어야 한다.
    @Column(name = "links", nullable = false, length = 2000, columnDefinition = "varchar(2000) default '[]' not null")
    private String links = "[]";

    protected ErdMemo() {
    }

    public ErdMemo(Long roomId, String memoKey, String text, String color, double posX, double posY, int sortOrder,
                   String links) {
        this.roomId = roomId;
        this.memoKey = memoKey;
        this.text = text;
        this.color = color.isBlank() ? DEFAULT_COLOR : color;
        this.posX = posX;
        this.posY = posY;
        this.sortOrder = sortOrder;
        this.links = links == null || links.isBlank() ? "[]" : links;
    }

    public void update(String text, String color, String links) {
        this.text = text;
        this.color = color.isBlank() ? DEFAULT_COLOR : color;
        this.links = links == null || links.isBlank() ? "[]" : links;
    }

    public void updateLinks(String links) {
        this.links = links == null || links.isBlank() ? "[]" : links;
    }

    public void moveTo(double x, double y) {
        this.posX = x;
        this.posY = y;
    }

    /** null을 넘기면 기본 크기로 되돌린다. */
    public void resizeTo(Double width, Double height) {
        this.width = width;
        this.height = height;
    }

    public Long getId() {
        return id;
    }

    public Long getRoomId() {
        return roomId;
    }

    public String getMemoKey() {
        return memoKey;
    }

    public String getText() {
        return text;
    }

    public String getColor() {
        return color;
    }

    public double getPosX() {
        return posX;
    }

    public double getPosY() {
        return posY;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public String getLinks() {
        return links;
    }

    public Double getWidth() {
        return width;
    }

    public Double getHeight() {
        return height;
    }
}
