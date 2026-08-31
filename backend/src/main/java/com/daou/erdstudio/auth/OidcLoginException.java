package com.daou.erdstudio.auth;

/** SSO 로그인 실패. code 는 콜백이 /?login_error={code} 로 302 할 때 브라우저에 노출하는 유일한 정보다. */
public class OidcLoginException extends RuntimeException {

    /** state / exchange / profile / username / conflict */
    private final String code;

    public OidcLoginException(String code, String message) {
        super(message);
        this.code = code;
    }

    public OidcLoginException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
