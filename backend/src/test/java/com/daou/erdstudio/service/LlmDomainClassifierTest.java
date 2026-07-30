package com.daou.erdstudio.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LlmDomainClassifierTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private LlmDomainClassifier withKey(String key) {
        return new LlmDomainClassifier(key, "claude-haiku-4-5-20251001", 12, mapper);
    }

    @Test
    void API키가_없으면_비활성화된다() {
        assertThat(withKey("").isEnabled()).isFalse();
        assertThat(withKey("   ").isEnabled()).isFalse();
        assertThat(withKey(null).isEnabled()).isFalse();
    }

    @Test
    void 비활성화_상태면_빈_결과를_반환해_사전방식으로_폴백하게_한다() {
        Map<String, String> input = new LinkedHashMap<>();
        input.put("donut_user", "회원 정보");
        assertThat(withKey("").classifyAll(input)).isEmpty();
    }

    @Test
    void API키가_있으면_활성화된다() {
        assertThat(withKey("sk-ant-test").isEnabled()).isTrue();
    }

    @Test
    void 호출_실패시_예외없이_빈_결과를_반환한다() {
        // 잘못된 키 → 인증 실패. 예외가 전파되지 않고 빈 맵이어야 한다.
        Map<String, String> input = new LinkedHashMap<>();
        input.put("donut_user", "회원 정보");
        assertThat(withKey("sk-ant-invalid-key-for-test").classifyAll(input)).isEmpty();
    }

    @Test
    void 테이블이_없으면_호출하지_않는다() {
        assertThat(withKey("sk-ant-test").classifyAll(Map.of())).isEmpty();
    }
}
