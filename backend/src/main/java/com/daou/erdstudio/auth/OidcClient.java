package com.daou.erdstudio.auth;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * authentik OIDC 엔드포인트 호출. discovery → 코드교환 → userinfo 3종만 담당한다.
 *
 * 사내 SSL 검사 장비(WAF) 호환을 위해 HttpURLConnection 기반 팩토리 + 일반 User-Agent 를 사용한다.
 */
@Component
public class OidcClient {

    private static final String USER_AGENT = "ErdStudio/0.1";
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    private final RestClient rest;
    private final OidcProperties props;

    /** 첫 성공 응답을 캐시한다. issuer 는 기동 후 바뀌지 않는다. */
    private volatile Discovery cached;

    public OidcClient(OidcProperties props) {
        this.props = props;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        this.rest = RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    /** issuer + ".well-known/openid-configuration" 조회. 성공하면 재요청하지 않는다. */
    public Discovery discovery() {
        Discovery local = cached;
        if (local != null) {
            return local;
        }
        String issuer = props.getIssuer();
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException("erd.oidc.issuer 설정이 비어 있습니다.");
        }
        String url = (issuer.endsWith("/") ? issuer : issuer + "/") + ".well-known/openid-configuration";
        JsonNode body = rest.get()
                .uri(url)
                .header(HttpHeaders.USER_AGENT, USER_AGENT)
                .accept(MediaType.ALL)
                .retrieve()
                .body(JsonNode.class);
        if (body == null) {
            throw new IllegalStateException("OIDC discovery 응답이 비어 있습니다.");
        }
        local = new Discovery(text(body, "authorization_endpoint"),
                text(body, "token_endpoint"),
                text(body, "userinfo_endpoint"));
        cached = local;
        return local;
    }

    /** authorization code → access_token. client_secret 은 바디로 전달한다(로그 출력 금지). */
    public String exchangeCode(String code, String codeVerifier) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("code_verifier", codeVerifier);
        form.add("client_id", props.getClientId());
        form.add("client_secret", props.getClientSecret());
        form.add("redirect_uri", props.getRedirectUri());
        JsonNode body = rest.post()
                .uri(discovery().tokenEndpoint())
                .header(HttpHeaders.USER_AGENT, USER_AGENT)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.ALL)
                .body(form)
                .retrieve()
                .body(JsonNode.class);
        String token = body == null ? null : text(body, "access_token");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("토큰 응답에 access_token 이 없습니다.");
        }
        return token;
    }

    /** access_token 으로 신원 조회. 서명 검증 없이 이 응답만 신뢰한다(TLS 직접 호출이 신뢰 기반). */
    public UserInfo userInfo(String accessToken) {
        JsonNode body = rest.get()
                .uri(discovery().userinfoEndpoint())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header(HttpHeaders.USER_AGENT, USER_AGENT)
                .accept(MediaType.ALL)
                .retrieve()
                .body(JsonNode.class);
        if (body == null) {
            throw new IllegalStateException("userinfo 응답이 비어 있습니다.");
        }
        List<String> groups = new ArrayList<>();
        JsonNode node = body.get("groups");
        if (node != null && node.isArray()) {
            node.forEach(g -> groups.add(g.asText()));
        }
        return new UserInfo(text(body, "sub"), text(body, "preferred_username"), text(body, "name"), groups);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    public record Discovery(String authorizationEndpoint, String tokenEndpoint, String userinfoEndpoint) {
    }

    public record UserInfo(String sub, String preferredUsername, String name, List<String> groups) {
    }
}
