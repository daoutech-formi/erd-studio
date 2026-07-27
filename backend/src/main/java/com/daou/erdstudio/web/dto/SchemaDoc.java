package com.daou.erdstudio.web.dto;

import java.util.List;
import java.util.Map;

/**
 * 프론트엔드와 주고받는 전체 스키마 문서.
 * tables 행: [name, domainKey, description, hub, posX, posY] (뒤 3개는 생략/NULL 허용)
 * relations 행: [childName, parentName, label?]
 * columns 값 행: [name, colType, comment, flag?]
 */
public record SchemaDoc(
        Map<String, DomainDef> domains,
        List<List<Object>> tables,
        List<List<Object>> relations,
        Map<String, List<List<Object>>> columns
) {

    public record DomainDef(String name, String color) {
    }
}
