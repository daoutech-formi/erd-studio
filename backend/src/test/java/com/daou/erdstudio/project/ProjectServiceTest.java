package com.daou.erdstudio.project;

import com.daou.erdstudio.repository.ErdRoomRepository;
import com.daou.erdstudio.service.RoomService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Transactional
class ProjectServiceTest {

    @Autowired
    ProjectService projectService;
    @Autowired
    ProjectRepository projectRepository;
    @Autowired
    RoomService roomService;
    @Autowired
    ErdRoomRepository roomRepository;

    @AfterEach
    void clearContext() {
        ProjectContext.clear();
    }

    @Test
    void legacy_보장은_멱등이다() {
        Project first = projectService.ensureLegacy();
        Project second = projectService.ensureLegacy();

        assertThat(first.getId()).isEqualTo(second.getId());
        assertThat(projectRepository.findAllByOrderByIdAsc().stream()
                .filter(p -> ProjectService.LEGACY_SLUG.equals(p.getSlug()))).hasSize(1);
    }

    @Test
    void 컨텍스트가_없으면_legacy로_폴백한다() {
        assertThat(projectService.currentProjectId()).isEqualTo(projectService.ensureLegacy().getId());
    }

    @Test
    void 프로젝트를_만들면_slug가_자동_생성되고_이름_중복은_거부된다() {
        Project p = projectService.create("애드웰");

        assertThat(p.getSlug()).matches("p[0-9a-z]{6}");
        assertThat(p.getName()).isEqualTo("애드웰");
        assertThatThrownBy(() -> projectService.create("애드웰"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> projectService.create("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 방은_현재_프로젝트에_소속되고_목록이_프로젝트별로_갈린다() {
        Project adwell = projectService.create("애드웰2");

        ProjectContext.set(adwell.getId());
        roomService.create("정산 ERD", "홍길동");
        ProjectContext.clear();
        roomService.create("레거시 방", "홍길동");

        ProjectContext.set(adwell.getId());
        assertThat(roomService.list()).extracting("name").containsExactly("정산 ERD");
        ProjectContext.clear();
        assertThat(roomService.list()).extracting("name").contains("레거시 방").doesNotContain("정산 ERD");
    }

    @Test
    void 같은_방_이름을_다른_프로젝트에는_만들_수_있고_같은_프로젝트에는_못_만든다() {
        Project a = projectService.create("프로젝트A");
        Project b = projectService.create("프로젝트B");

        ProjectContext.set(a.getId());
        roomService.create("공용 ERD", "홍길동");
        assertThatThrownBy(() -> roomService.create("공용 ERD", "홍길동"))
                .isInstanceOf(IllegalArgumentException.class);

        ProjectContext.set(b.getId());
        assertThat(roomService.create("공용 ERD", "홍길동").getProjectId()).isEqualTo(b.getId());
    }

    @Test
    void 빈_프로젝트만_삭제할_수_있고_legacy는_삭제할_수_없다() {
        Project p = projectService.create("삭제될 프로젝트");
        projectService.delete(p.getSlug());
        assertThat(projectRepository.findBySlug(p.getSlug())).isEmpty();

        Project withRoom = projectService.create("방있는 프로젝트");
        ProjectContext.set(withRoom.getId());
        roomService.create("방", "홍길동");
        assertThatThrownBy(() -> projectService.delete(withRoom.getSlug()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> projectService.delete(ProjectService.LEGACY_SLUG))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
