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

    protected ErdMemo() {
    }

    public ErdMemo(Long roomId, String memoKey, String text, String color, double posX, double posY, int sortOrder) {
        this.roomId = roomId;
        this.memoKey = memoKey;
        this.text = text;
        this.color = color.isBlank() ? DEFAULT_COLOR : color;
        this.posX = posX;
        this.posY = posY;
        this.sortOrder = sortOrder;
    }

    public void update(String text, String color) {
        this.text = text;
        this.color = color.isBlank() ? DEFAULT_COLOR : color;
    }

    public void moveTo(double x, double y) {
        this.posX = x;
        this.posY = y;
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
}
