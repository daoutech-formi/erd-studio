package com.daou.erdstudio.auth;

import com.daou.erdstudio.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 로그인 계정. 신원은 authentik(IdP)이 판정하고 이 테이블은 그 결과만 담는다. */
@Entity
@Table(name = "app_user")
public class AppUser extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50, unique = true)
    private String username;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    /** authentik(IdP)의 subject. 연결 전 기존 계정은 null 이다. */
    @Column(name = "oidc_sub", length = 255, unique = true)
    private String oidcSub;

    /** 전 프로젝트 관리 + 계정·권한 관리 API 접근 권한. */
    @Column(name = "super_admin", nullable = false)
    private boolean superAdmin;

    /** 앱에서 부여한 최고관리자 권한. IdP 그룹 판정과 별개로 유지된다. */
    @Column(name = "super_admin_granted", nullable = false)
    private boolean superAdminGranted;

    protected AppUser() {
    }

    public AppUser(String username, String displayName, String oidcSub, boolean superAdmin) {
        this.username = username;
        this.displayName = displayName;
        this.oidcSub = oidcSub;
        this.superAdmin = superAdmin;
    }

    /** 아이디로 찾은 기존 계정을 IdP subject 에 연결한다. */
    public void linkOidc(String oidcSub) {
        this.oidcSub = oidcSub;
    }

    /** 매 로그인마다 IdP 프로필·그룹을 반영한다. 앱에서 부여한 권한은 그룹과 무관하게 유지한다. */
    public void syncProfile(String displayName, boolean idpSuperAdmin) {
        this.displayName = displayName;
        this.superAdmin = idpSuperAdmin || this.superAdminGranted;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getOidcSub() {
        return oidcSub;
    }

    public boolean isSuperAdmin() {
        return superAdmin;
    }

    public boolean isSuperAdminGranted() {
        return superAdminGranted;
    }
}
