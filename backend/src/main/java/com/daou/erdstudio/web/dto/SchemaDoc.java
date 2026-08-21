package com.daou.erdstudio.web.dto;

import java.util.List;
import java.util.Map;

/**
 * 프론트엔드와 주고받는 전체 스키마 문서.
 * tables 행: [name, domainKey, description, hub, posX, posY] (뒤 3개는 생략/NULL 허용)
 * relations 행: [childName, parentName, label?]
 * columns 값 행: [name, colType, comment, flag?]
 * memos 행: [id, text, x, y, color, links?, w?, h?] (links: 연결된 테이블명 배열 / w·h: 사용자 지정 크기, 없으면 생략)
 */
public record SchemaDoc(
        Map<String, DomainDef> domains,
        List<List<Object>> tables,
        List<List<Object>> relations,
        Map<String, List<List<Object>>> columns,
        List<List<Object>> memos
) {

    /** 메모 도입 전의 문서(JSON 저장본·이력 스냅샷)도 그대로 받아들인다. */
    public SchemaDoc {
        if (memos == null) {
            memos = List.of();
        }
    }

    /** 메모 없는 문서를 만드는 편의 생성자. */
    public SchemaDoc(Map<String, DomainDef> domains, List<List<Object>> tables,
                     List<List<Object>> relations, Map<String, List<List<Object>>> columns) {
        this(domains, tables, relations, columns, List.of());
    }

    public record DomainDef(String name, String color) {
    }
}
