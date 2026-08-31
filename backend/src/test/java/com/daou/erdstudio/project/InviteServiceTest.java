package com.daou.erdstudio.project;

import com.daou.erdstudio.auth.AppUser;
import com.daou.erdstudio.auth.AppUserRepository;
import com.daou.erdstudio.auth.Principal;
import com.daou.erdstudio.common.UnauthorizedException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Transactional
class InviteServiceTest {

    @Autowired
    InviteService inviteService;
    @Autowired
    ProjectService projectService;
    @Autowired
    ProjectRepository projectRepository;
    @Autowired
    ProjectInviteRepository inviteRepository;
    @Autowired
    ProjectMemberRepository memberRepository;
    @Autowired
    AppUserRepository appUserRepository;

    private AppUser user(String username) {
        return appUserRepository.save(new AppUser(username, username, "sub-" + username, false));
    }

    private static Principal principalOf(AppUser u) {
        return new Principal(u.getId(), u.getUsername(), false);
    }

    @Test
    void 생성하면_고유_토큰과_기본값을_가진다() {
        Project p = projectService.create("초대테스트1");
        InviteService.InviteView invite = inviteService.create(p.getId(), ProjectRole.VIEWER, null, null, null);

        assertThat(invite.token()).hasSize(32).matches("[0-9a-f]+");
        assertThat(invite.expiresAt()).isNull();
        assertThat(invite.maxUses()).isZero();
        assertThat(invite.usedCount()).isZero();
        assertThat(invite.exhausted()).isFalse();
        assertThat(inviteService.list(p.getId())).extracting(InviteService.InviteView::token)
                .containsExactly(invite.token());
    }

    @Test
    void 수락하면_지정_역할의_멤버가_되고_사용횟수가_증가한다() {
        Project p = projectService.create("초대테스트2");
        InviteService.InviteView invite = inviteService.create(p.getId(), ProjectRole.EDITOR, 7, 5, null);
        Principal joiner = principalOf(user("joiner1"));

        InviteService.AcceptView result = inviteService.accept(invite.token(), joiner);

        assertThat(result.ok()).isTrue();
        assertThat(result.projectSlug()).isEqualTo(p.getSlug());
        assertThat(result.role()).isEqualTo(ProjectRole.EDITOR);
        assertThat(result.alreadyMember()).isFalse();
        assertThat(memberRepository.findByProjectIdAndUserId(p.getId(), joiner.userId()))
                .hasValueSatisfying(m -> assertThat(m.getRole()).isEqualTo(ProjectRole.EDITOR));
        assertThat(inviteRepository.findByToken(invite.token()).orElseThrow().getUsedCount()).isEqualTo(1);
    }

    @Test
    void 게스트의_수락은_401_이다() {
        Project p = projectService.create("초대테스트3");
        InviteService.InviteView invite = inviteService.create(p.getId(), ProjectRole.VIEWER, null, null, null);

        assertThatThrownBy(() -> inviteService.accept(invite.token(), Principal.guest()))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void 만료된_링크는_거절된다() {
        Project p = projectService.create("초대테스트4");
        ProjectInvite expired = inviteRepository.save(new ProjectInvite(
                p.getId(), "expiredtoken0000000000000000000a", ProjectRole.VIEWER,
                LocalDateTime.now().minusMinutes(1), 0, null));
        Principal joiner = principalOf(user("joiner2"));

        assertThat(inviteService.preview(expired.getToken(), joiner).valid()).isFalse();
        assertThatThrownBy(() -> inviteService.accept(expired.getToken(), joiner))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("만료");
        assertThat(memberRepository.findByProjectIdAndUserId(p.getId(), joiner.userId())).isEmpty();
    }

    @Test
    void 사용횟수를_소진한_링크는_거절된다() {
        Project p = projectService.create("초대테스트5");
        InviteService.InviteView invite = inviteService.create(p.getId(), ProjectRole.VIEWER, null, 1, null);
        Principal first = principalOf(user("joiner3"));
        Principal second = principalOf(user("joiner4"));

        inviteService.accept(invite.token(), first);
        assertThatThrownBy(() -> inviteService.accept(invite.token(), second))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("소진");
        assertThat(memberRepository.findByProjectIdAndUserId(p.getId(), second.userId())).isEmpty();
    }

    @Test
    void 기존_멤버의_재수락은_역할과_사용횟수를_바꾸지_않는다() {
        Project p = projectService.create("초대테스트6");
        Principal admin = principalOf(user("admin6"));
        memberRepository.save(new ProjectMember(p.getId(), admin.userId(), ProjectRole.ADMIN));
        InviteService.InviteView invite = inviteService.create(p.getId(), ProjectRole.VIEWER, null, null, null);

        InviteService.AcceptView result = inviteService.accept(invite.token(), admin);

        assertThat(result.alreadyMember()).isTrue();
        assertThat(memberRepository.findByProjectIdAndUserId(p.getId(), admin.userId()))
                .hasValueSatisfying(m -> assertThat(m.getRole()).isEqualTo(ProjectRole.ADMIN));
        assertThat(inviteRepository.findByToken(invite.token()).orElseThrow().getUsedCount()).isZero();
    }

    @Test
    void 폐기한_링크는_수락할_수_없고_남의_프로젝트_초대는_폐기할_수_없다() {
        Project p = projectService.create("초대테스트7");
        Project other = projectService.create("초대테스트7b");
        InviteService.InviteView invite = inviteService.create(p.getId(), ProjectRole.VIEWER, null, null, null);

        assertThatThrownBy(() -> inviteService.revoke(other.getId(), invite.id()))
                .isInstanceOf(IllegalArgumentException.class);

        inviteService.revoke(p.getId(), invite.id());
        assertThatThrownBy(() -> inviteService.accept(invite.token(), principalOf(user("joiner5"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 프로젝트를_삭제하면_초대도_삭제된다() {
        Project p = projectService.create("초대테스트8");
        inviteService.create(p.getId(), ProjectRole.VIEWER, null, null, null);

        projectService.delete(p.getSlug());
        assertThat(inviteRepository.findByProjectIdOrderByIdAsc(p.getId())).isEmpty();
    }
}
