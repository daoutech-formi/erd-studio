package com.daou.erdstudio.project;

/** 프로젝트 멤버십 역할. 관리자=멤버·프로젝트 관리+쓰기, 편집자=쓰기, 뷰어=읽기. */
public enum ProjectRole {
    ADMIN(Level.ADMIN),
    EDITOR(Level.WRITE),
    VIEWER(Level.READ);

    private final Level level;

    ProjectRole(Level level) {
        this.level = level;
    }

    public Level level() {
        return level;
    }
}
