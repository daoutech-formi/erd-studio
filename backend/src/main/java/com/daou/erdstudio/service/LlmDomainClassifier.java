package com.daou.erdstudio.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic API 로 테이블 목록을 한 번에 도메인 분류한다. (선택 기능)
 *
 * API 키(anthropic.api-key / ANTHROPIC_API_KEY)가 없으면 {@link #isEnabled()} 가 false 이고,
 * 호출 실패·타임아웃·형식 오류 시에도 빈 결과를 반환한다.
 * 호출자는 사전+가중치 방식({@link DomainClassifier})으로 폴백해야 한다.
 */
@Component
public class LlmDomainClassifier {

    private static final Logger log = LoggerFactory.getLogger(LlmDomainClassifier.class);
    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";
    private static final int MAX_TABLES = 400;

    private final String apiKey;
    private final String model;
    private final int maxDomains;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public LlmDomainClassifier(@Value("${anthropic.api-key:${ANTHROPIC_API_KEY:}}") String apiKey,
                               @Value("${anthropic.model:claude-haiku-4-5-20251001}") String model,
                               @Value("${erd.max-domains:12}") int maxDomains,
                               ObjectMapper objectMapper) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.maxDomains = maxDomains;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder().baseUrl(API_URL).build();
    }

    public boolean isEnabled() {
        return !apiKey.isEmpty();
    }

    /**
     * 테이블명→코멘트 맵을 받아 테이블명→도메인명(한글) 맵을 돌려준다.
     * 비활성/실패 시 빈 맵을 반환한다(예외를 던지지 않는다).
     */
    public Map<String, String> classifyAll(Map<String, String> commentByTable) {
        if (!isEnabled() || commentByTable.isEmpty() || commentByTable.size() > MAX_TABLES) {
            return Map.of();
        }
        try {
            String response = callApi(buildPrompt(commentByTable));
            Map<String, String> parsed = parseResult(response, commentByTable.keySet());
            if (parsed.isEmpty()) {
                log.warn("LLM 도메인 분류 결과가 비어 있습니다. 사전 방식으로 폴백합니다.");
            }
            return parsed;
        } catch (Exception e) {
            log.warn("LLM 도메인 분류 실패 — 사전 방식으로 폴백합니다: {}", e.getMessage());
            return Map.of();
        }
    }

    private String buildPrompt(Map<String, String> commentByTable) {
        StringBuilder sb = new StringBuilder();
        sb.append("다음은 하나의 서비스 데이터베이스에 속한 테이블 목록입니다. ")
                .append("각 줄은 `테이블명 | 테이블 코멘트` 형식입니다.\n\n")
                .append("이 스키마를 업무 도메인으로 분류하세요. 규칙:\n")
                .append("1. 도메인 개수는 최대 ").append(maxDomains).append("개. 의미가 가까운 것은 합칩니다.\n")
                .append("2. 도메인명은 짧은 한글(예: 회원/인증, 주문/배송, 통계). 12자 이내.\n")
                .append("3. 사용자 서비스용 테이블과 어드민/운영용 테이블은 구분합니다.\n")
                .append("4. 통계·로그·이력 성격이 뚜렷한 테이블은 해당 도메인으로 모읍니다.\n")
                .append("5. 'xxx 옵션', 'xxx 이력'처럼 수식이 붙어도 주체(xxx)를 기준으로 분류합니다.\n")
                .append("6. 모든 테이블을 빠짐없이 배정합니다. 애매하면 '기타'.\n\n")
                .append("출력은 다른 설명 없이 JSON 객체만. 형식: {\"테이블명\": \"도메인명\", ...}\n\n")
                .append("테이블 목록:\n");
        for (Map.Entry<String, String> e : commentByTable.entrySet()) {
            sb.append(e.getKey()).append(" | ")
                    .append(e.getValue() == null || e.getValue().isBlank() ? "-" : e.getValue())
                    .append('\n');
        }
        return sb.toString();
    }

    private String callApi(String prompt) {
        Map<String, Object> body = Map.of(
                "model", model,
                "max_tokens", 8192,
                "messages", List.of(Map.of("role", "user", "content", prompt)));
        return restClient.post()
                .uri(API_URL)
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
    }

    /** 응답 본문에서 JSON 객체를 뽑아 알려진 테이블에 대해서만 매핑을 만든다. */
    private Map<String, String> parseResult(String response, java.util.Set<String> knownTables)
            throws Exception {
        JsonNode root = objectMapper.readTree(response);
        StringBuilder text = new StringBuilder();
        for (JsonNode block : root.path("content")) {
            if ("text".equals(block.path("type").asText())) {
                text.append(block.path("text").asText());
            }
        }
        String json = extractJsonObject(text.toString());
        if (json == null) {
            return Map.of();
        }
        JsonNode mapping = objectMapper.readTree(json);
        Map<String, String> out = new LinkedHashMap<>();
        Iterator<String> it = mapping.fieldNames();
        while (it.hasNext()) {
            String table = it.next();
            String domain = mapping.path(table).asText("").trim();
            if (knownTables.contains(table) && !domain.isEmpty() && domain.length() <= 20) {
                out.put(table, domain);
            }
        }
        return out;
    }

    /** 텍스트에서 첫 번째 완전한 JSON 객체를 잘라낸다(코드블록/설명 혼재 대비). */
    private String extractJsonObject(String text) {
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
            } else if (ch == '{') {
                depth++;
            } else if (ch == '}' && --depth == 0) {
                return text.substring(start, i + 1);
            }
        }
        return null;
    }

    /** 테스트/진단용 — 사용 중인 설정 요약. */
    public List<String> describe() {
        List<String> out = new ArrayList<>();
        out.add("enabled=" + isEnabled());
        out.add("model=" + model);
        out.add("maxDomains=" + maxDomains);
        return out;
    }
}
