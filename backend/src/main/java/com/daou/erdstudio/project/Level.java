package com.daou.erdstudio.project;

/** 프로젝트에 대한 유효 권한 수준. 선언 순서 = 권한 강도(ordinal 비교). */
public enum Level {
    NONE, READ, WRITE, ADMIN;

    public boolean satisfies(Level required) {
        return ordinal() >= required.ordinal();
    }
}
