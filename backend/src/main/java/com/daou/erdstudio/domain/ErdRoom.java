package com.daou.erdstudio.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * ERD 방(협업 공간). 모든 ERD 데이터(도메인·테이블·컬럼·관계·이력·락·접속자)는 방 단위로 분리된다.
 *
 * <p>주의(기존 배포 마이그레이션): erd_domain / erd_table / erd_relation / erd_history 에
 * room_id (NOT NULL) 컬럼이 추가되었다. {@code ddl-auto: update} 는 데이터가 이미 들어 있는
 * 테이블에 NOT NULL 컬럼을 추가할 수 없으므로, 운영 DB 는 erd_* 테이블을 drop 후 재생성하거나
 * room_id 를 수동으로 백필(기본 방 id 로 UPDATE 후 NOT NULL 설정)해야 한다.
 * 또한 erd_domain 의 PK 가 key(문자열) → id(시퀀스) 로 바뀌었다.
 */
@Entity
@Table(name = "erd_room")
public class ErdRoom {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ErdRoom() {
    }

    public ErdRoom(String name, String createdBy) {
        this.name = name;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
