package com.daou.erdstudio.project;

import com.daou.erdstudio.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 프로젝트 초대 링크. 링크를 아는 로그인 사용자가 지정된 역할로 합류한다.
 * expires_at null = 무기한, max_uses 0 = 무제한.
 */
@Entity
@Table(name = "project_invite")
public class ProjectInvite extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(nullable = false, unique = true, length = 64)
    private String token;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProjectRole role;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "max_uses", nullable = false)
    private int maxUses;

    @Column(name = "used_count", nullable = false)
    private int usedCount;

    @Column(name = "created_by")
    private Long createdBy;

    protected ProjectInvite() {
    }

    public ProjectInvite(Long projectId, String token, ProjectRole role,
                         LocalDateTime expiresAt, int maxUses, Long createdBy) {
        this.projectId = projectId;
        this.token = token;
        this.role = role;
        this.expiresAt = expiresAt;
        this.maxUses = maxUses;
        this.usedCount = 0;
        this.createdBy = createdBy;
    }

    public boolean expired() {
        return expiresAt != null && expiresAt.isBefore(LocalDateTime.now());
    }

    public boolean usedUp() {
        return maxUses > 0 && usedCount >= maxUses;
    }

    /** 더 이상 수락에 쓸 수 없는 상태(만료 또는 사용횟수 소진). */
    public boolean exhausted() {
        return expired() || usedUp();
    }

    public void use() {
        this.usedCount++;
    }

    public Long getId() {
        return id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public String getToken() {
        return token;
    }

    public ProjectRole getRole() {
        return role;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public int getMaxUses() {
        return maxUses;
    }

    public int getUsedCount() {
        return usedCount;
    }

    public Long getCreatedBy() {
        return createdBy;
    }
}
