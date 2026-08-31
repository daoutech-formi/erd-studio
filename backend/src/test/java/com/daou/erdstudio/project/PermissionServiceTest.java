package com.daou.erdstudio.project;

import com.daou.erdstudio.auth.AppUser;
import com.daou.erdstudio.auth.AppUserRepository;
import com.daou.erdstudio.auth.OidcProperties;
import com.daou.erdstudio.auth.Principal;
import com.daou.erdstudio.common.ForbiddenException;
import com.daou.erdstudio.common.UnauthorizedException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Transactional
class PermissionServiceTest {

    @Autowired
    PermissionService disabledService;   // 테스트 컨텍스트는 erd.oidc.enabled=false
    @Autowired
    ProjectRepository projectRepository;
    @Autowired
    ProjectMemberRepository memberRepository;
    @Autowired
    AppUserRepository appUserRepository;
    @Autowired
    ProjectService projectService;

    /** enabled=true 인 권한 서비스 — SSO 켠 환경을 시뮬레이션한다. */
    private PermissionService enabledService() {
        OidcProperties props = new OidcProperties();
        props.setEnabled(true);
        return new PermissionService(props, projectRepository, memberRepository, appUserRepository);
    }

    private AppUser user(String username, boolean superAdmin) {
        return appUserRepository.save(new AppUser(username, username, "sub-" + username, superAdmin));
    }

    private static Principal principalOf(AppUser u) {
        return new Principal(u.getId(), u.getUsername(), u.isSuperAdmin());
    }

    @Test
    void SSO_미사용이면_게스트도_ADMIN_이다() {
        Project p = projectService.create("권한테스트0");
        assertThat(disabledService.levelFor(Principal.guest(), p.getId())).isEqualTo(Level.ADMIN);
        assertThat(disabledService.visibleProjects(Principal.guest()))
                .extracting(Project::getId).contains(p.getId());
    }

    @Test
    void 게스트는_NONE_이고_require는_401을_던진다() {
        PermissionService service = enabledService();
        Project p = projectService.create("권한테스트1");

        assertThat(service.levelFor(Principal.guest(), p.getId())).isEqualTo(Level.NONE);
        assertThat(service.visibleProjects(Principal.guest())).isEmpty();
        assertThatThrownBy(() -> service.require(Principal.guest(), p.getId(), Level.READ))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void superAdmin은_모든_프로젝트_ADMIN_이다() {
        PermissionService service = enabledService();
        Project p = projectService.create("권한테스트2");
        Principal admin = principalOf(user("sadmin", true));

        assertThat(service.levelFor(admin, p.getId())).isEqualTo(Level.ADMIN);
        assertThat(service.visibleProjects(admin)).extracting(Project::getId).contains(p.getId());
        assertThat(service.roleOf(admin, p.getId())).isEqualTo(ProjectRole.ADMIN);
    }

    @Test
    void 멤버_역할이_레벨로_매핑되고_비멤버는_NONE_이다() {
        PermissionService service = enabledService();
        Project p = projectService.create("권한테스트3");
        Principal viewer = principalOf(user("viewer1", false));
        Principal editor = principalOf(user("editor1", false));
        Principal stranger = principalOf(user("stranger1", false));
        memberRepository.save(new ProjectMember(p.getId(), viewer.userId(), ProjectRole.VIEWER));
        memberRepository.save(new ProjectMember(p.getId(), editor.userId(), ProjectRole.EDITOR));

        assertThat(service.levelFor(viewer, p.getId())).isEqualTo(Level.READ);
        assertThat(service.levelFor(editor, p.getId())).isEqualTo(Level.WRITE);
        assertThat(service.levelFor(stranger, p.getId())).isEqualTo(Level.NONE);
        assertThatThrownBy(() -> service.require(viewer, p.getId(), Level.WRITE))
                .isInstanceOf(ForbiddenException.class);
        service.require(editor, p.getId(), Level.WRITE);   // 통과

        // 가시성 — 멤버는 자기 프로젝트만, 비멤버는 안 보인다.
        assertThat(service.visibleProjects(viewer)).extracting(Project::getId).containsExactly(p.getId());
        assertThat(service.visibleProjects(stranger)).isEmpty();
    }

    @Test
    void 멤버_교체는_ADMIN_1명_이상을_요구한다() {
        PermissionService service = enabledService();
        Project p = projectService.create("권한테스트4");
        AppUser a = user("adm2", false);
        AppUser b = user("edi2", false);

        service.replaceMembers(p.getId(), List.of(
                new PermissionService.MemberInput(a.getId(), ProjectRole.ADMIN),
                new PermissionService.MemberInput(b.getId(), ProjectRole.EDITOR)));
        assertThat(service.members(p.getId())).hasSize(2);

        assertThatThrownBy(() -> service.replaceMembers(p.getId(), List.of(
                new PermissionService.MemberInput(b.getId(), ProjectRole.EDITOR))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 생성자는_ADMIN_멤버가_되고_프로젝트_삭제시_멤버십도_지워진다() {
        AppUser creator = user("creator1", false);
        Project p = projectService.create("권한테스트5", creator.getId());

        assertThat(memberRepository.findByProjectIdAndUserId(p.getId(), creator.getId()))
                .hasValueSatisfying(m -> assertThat(m.getRole()).isEqualTo(ProjectRole.ADMIN));

        projectService.delete(p.getSlug());
        assertThat(memberRepository.findByProjectId(p.getId())).isEmpty();
    }
}
