package com.daou.erdstudio.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** authentik OIDC SSO 설정. enabled=false 면 로그인 진입점이 503 이고 전원 게스트로 동작한다. */
@ConfigurationProperties("erd.oidc")
public class OidcProperties {

    /** false 면 SSO 로그인 진입점이 503 을 반환하고 전원 게스트로 동작한다(기존 동작 유지). */
    private boolean enabled = false;

    /** authentik issuer URL. 끝 슬래시 포함. discovery = issuer + ".well-known/openid-configuration" */
    private String issuer;

    private String clientId;

    private String clientSecret;

    /** authentik 에 등록한 콜백 URL. 토큰 교환 시 동일 값을 그대로 보낸다. */
    private String redirectUri;

    /** 이 그룹에 속한 사용자를 최고관리자로 동기화한다. */
    private String adminGroup = "erd-admins";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String getRedirectUri() {
        return redirectUri;
    }

    public void setRedirectUri(String redirectUri) {
        this.redirectUri = redirectUri;
    }

    public String getAdminGroup() {
        return adminGroup;
    }

    public void setAdminGroup(String adminGroup) {
        this.adminGroup = adminGroup;
    }
}
