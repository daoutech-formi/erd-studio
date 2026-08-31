package com.daou.erdstudio.project;

import com.daou.erdstudio.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 프로젝트 = ERD 방의 묶음(격리 단위). slug 는 X-Project-Id 헤더·localStorage 에 쓰는 식별자다. */
@Entity
@Table(name = "project")
public class Project extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32, unique = true)
    private String slug;

    @Column(nullable = false, length = 50, unique = true)
    private String name;

    protected Project() {
    }

    public Project(String slug, String name) {
        this.slug = slug;
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public String getSlug() {
        return slug;
    }

    public String getName() {
        return name;
    }
}
