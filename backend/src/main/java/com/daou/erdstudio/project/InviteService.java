package com.daou.erdstudio.project;

import com.daou.erdstudio.auth.Principal;
import com.daou.erdstudio.common.UnauthorizedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

/**
 * 초대 링크 생성·폐기·수락. 관리(생성·목록·폐기)는 인터셉터가 프로젝트 ADMIN 을 보장하고,
 * 수락은 로그인 사용자면 누구나 — 링크 소지가 곧 초대다.
 * 이미 멤버인 계정의 수락은 역할을 바꾸지 않는다(관리자가 뷰어 링크로 강등되는 사고 방지).
 */
@Service
public class InviteService {

    private static final int TOKEN_BYTES = 16;
    private static final int MAX_USES_LIMIT = 1000;
    private static final int EXPIRES_DAYS_LIMIT = 365;

    private final ProjectInviteRepository inviteRepository;
    private final ProjectMemberRepository memberRepository;
    private final ProjectRepository projectRepository;

    private final SecureRandom random = new SecureRandom();

    public InviteService(ProjectInviteRepository inviteRepository,
                         ProjectMemberRepository memberRepository,
                         ProjectRepository projectRepository) {
        this.inviteRepository = inviteRepository;
        this.memberRepository = memberRepository;
        this.projectRepository = projectRepository;
    }

    @Transactional
    public InviteView create(Long projectId, ProjectRole role, Integer expiresInDays, Integer maxUses,
                             Long creatorUserId) {
        if (role == null) {
            throw new IllegalArgumentException("초대 역할을 선택하세요.");
        }
        if (expiresInDays != null && (expiresInDays < 1 || expiresInDays > EXPIRES_DAYS_LIMIT)) {
            throw new IllegalArgumentException("유효기간은 1~" + EXPIRES_DAYS_LIMIT + "일 사이여야 합니다.");
        }
        int uses = maxUses == null ? 0 : maxUses;
        if (uses < 0 || uses > MAX_USES_LIMIT) {
            throw new IllegalArgumentException("최대 사용 횟수는 0(무제한)~" + MAX_USES_LIMIT + " 사이여야 합니다.");
        }
        LocalDateTime expiresAt = expiresInDays == null ? null : LocalDateTime.now().plusDays(expiresInDays);
        ProjectInvite saved = inviteRepository.save(
                new ProjectInvite(projectId, newToken(), role, expiresAt, uses, creatorUserId));
        return toView(saved);
    }

    @Transactional(readOnly = true)
    public List<InviteView> list(Long projectId) {
        return inviteRepository.findByProjectIdOrderByIdAsc(projectId).stream()
                .map(InviteService::toView)
                .toList();
    }

    /** 초대 폐기. 다른 프로젝트의 초대 id 를 지우지 못하게 소속을 검증한다. */
    @Transactional
    public void revoke(Long projectId, Long inviteId) {
        ProjectInvite invite = inviteRepository.findById(inviteId)
                .filter(i -> i.getProjectId().equals(projectId))
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 초대입니다."));
        inviteRepository.delete(invite);
    }

    /** 수락 전 미리보기 — 무효 링크도 200 으로 내려 프론트가 사유를 그대로 보여준다. */
    @Transactional(readOnly = true)
    public PreviewView preview(String token, Principal principal) {
        ProjectInvite invite = inviteRepository.findByToken(token).orElse(null);
        if (invite == null) {
            return new PreviewView(null, null, false, "유효하지 않은 초대 링크입니다.", false);
        }
        String projectName = projectRepository.findById(invite.getProjectId())
                .map(Project::getName).orElse(null);
        if (projectName == null) {
            return new PreviewView(null, null, false, "유효하지 않은 초대 링크입니다.", false);
        }
        String reason = rejectReason(invite);
        boolean alreadyMember = principal != null && principal.authenticated()
                && memberRepository.findByProjectIdAndUserId(invite.getProjectId(), principal.userId()).isPresent();
        return new PreviewView(projectName, invite.getRole(), reason == null, reason, alreadyMember);
    }

    @Transactional
    public AcceptView accept(String token, Principal principal) {
        if (principal == null || !principal.authenticated()) {
            throw new UnauthorizedException("로그인이 필요합니다.");
        }
        ProjectInvite invite = inviteRepository.findByToken(token)
                .orElseThrow(() -> new IllegalArgumentException("유효하지 않은 초대 링크입니다."));
        Project project = projectRepository.findById(invite.getProjectId())
                .orElseThrow(() -> new IllegalArgumentException("유효하지 않은 초대 링크입니다."));
        String reason = rejectReason(invite);
        if (reason != null) {
            throw new IllegalArgumentException(reason);
        }
        boolean alreadyMember = memberRepository
                .findByProjectIdAndUserId(project.getId(), principal.userId()).isPresent();
        if (!alreadyMember) {
            memberRepository.save(new ProjectMember(project.getId(), principal.userId(), invite.getRole()));
            invite.use();
        }
        return new AcceptView(true, project.getSlug(), project.getName(), invite.getRole(), alreadyMember);
    }

    private static String rejectReason(ProjectInvite invite) {
        if (invite.expired()) {
            return "만료된 초대 링크입니다.";
        }
        if (invite.usedUp()) {
            return "사용 횟수를 모두 소진한 초대 링크입니다.";
        }
        return null;
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static InviteView toView(ProjectInvite i) {
        return new InviteView(i.getId(), i.getToken(), i.getRole(), i.getExpiresAt(),
                i.getMaxUses(), i.getUsedCount(), i.exhausted());
    }

    public record InviteView(Long id, String token, ProjectRole role, LocalDateTime expiresAt,
                             int maxUses, int usedCount, boolean exhausted) {
    }

    public record PreviewView(String projectName, ProjectRole role, boolean valid, String reason,
                              boolean alreadyMember) {
    }

    public record AcceptView(boolean ok, String projectSlug, String projectName, ProjectRole role,
                             boolean alreadyMember) {
    }
}
