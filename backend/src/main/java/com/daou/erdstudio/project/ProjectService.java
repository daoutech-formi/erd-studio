package com.daou.erdstudio.project;

import com.daou.erdstudio.repository.ErdRoomRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;

/** 프로젝트 목록·생성·삭제와 "현재 프로젝트" 해석. legacy 프로젝트가 항상 존재함을 보장한다. */
@Service
public class ProjectService {

    public static final String LEGACY_SLUG = "legacy";
    public static final String LEGACY_NAME = "레거시";

    private static final int MAX_NAME_LENGTH = 50;
    private static final int SLUG_RANDOM_LENGTH = 6;
    private static final char[] SLUG_CHARS = "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray();

    private final ProjectRepository projectRepository;
    private final ErdRoomRepository roomRepository;
    private final ProjectMemberRepository memberRepository;

    private final SecureRandom random = new SecureRandom();

    public ProjectService(ProjectRepository projectRepository, ErdRoomRepository roomRepository,
                          ProjectMemberRepository memberRepository) {
        this.projectRepository = projectRepository;
        this.roomRepository = roomRepository;
        this.memberRepository = memberRepository;
    }

    @Transactional(readOnly = true)
    public List<Project> list() {
        return projectRepository.findAllByOrderByIdAsc();
    }

    /** legacy 프로젝트를 반환한다. 없으면 만든다(부트스트랩이 먼저 보장하지만 안전망). */
    @Transactional
    public Project ensureLegacy() {
        return projectRepository.findBySlug(LEGACY_SLUG)
                .orElseGet(() -> projectRepository.save(new Project(LEGACY_SLUG, LEGACY_NAME)));
    }

    /** 요청의 현재 프로젝트 id. X-Project-Id 가 없거나 미지면 legacy 로 폴백한다. */
    @Transactional
    public Long currentProjectId() {
        Long fromHeader = ProjectContext.getProjectId();
        return fromHeader != null ? fromHeader : ensureLegacy().getId();
    }

    /** 방 응답에 실을 slug. 백필 전(null)이거나 지워진 프로젝트면 legacy 로 표기한다. */
    @Transactional(readOnly = true)
    public String slugOf(Long projectId) {
        if (projectId == null) {
            return LEGACY_SLUG;
        }
        return projectRepository.findById(projectId).map(Project::getSlug).orElse(LEGACY_SLUG);
    }

    @Transactional
    public Project create(String name) {
        return create(name, null);
    }

    /** 프로젝트를 만들고, 로그인 사용자가 만들면 그 계정을 ADMIN 멤버로 등록한다. */
    @Transactional
    public Project create(String name, Long creatorUserId) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("프로젝트 이름을 입력하세요.");
        }
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("프로젝트 이름은 " + MAX_NAME_LENGTH + "자 이내로 입력하세요.");
        }
        if (projectRepository.existsByName(trimmed)) {
            throw new IllegalArgumentException("이미 존재하는 프로젝트 이름입니다.");
        }
        Project project = projectRepository.save(new Project(newSlug(), trimmed));
        if (creatorUserId != null) {
            memberRepository.save(new ProjectMember(project.getId(), creatorUserId, ProjectRole.ADMIN));
        }
        return project;
    }

    /** 빈 프로젝트만 지울 수 있다. legacy 는 폴백 대상이므로 지울 수 없다. */
    @Transactional
    public void delete(String slug) {
        Project project = projectRepository.findBySlug(slug)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 프로젝트입니다."));
        if (LEGACY_SLUG.equals(project.getSlug())) {
            throw new IllegalArgumentException("레거시 프로젝트는 삭제할 수 없습니다.");
        }
        if (roomRepository.countByProjectId(project.getId()) > 0) {
            throw new IllegalArgumentException("방이 있는 프로젝트는 삭제할 수 없습니다. 방을 먼저 정리하세요.");
        }
        memberRepository.deleteByProjectId(project.getId());
        projectRepository.delete(project);
    }

    /** "p" + 랜덤 base36 6자. 이름이 한글이어도 URL/헤더에 안전하다. */
    private String newSlug() {
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder sb = new StringBuilder(SLUG_RANDOM_LENGTH + 1).append('p');
            for (int i = 0; i < SLUG_RANDOM_LENGTH; i++) {
                sb.append(SLUG_CHARS[random.nextInt(SLUG_CHARS.length)]);
            }
            String slug = sb.toString();
            if (!projectRepository.existsBySlug(slug)) {
                return slug;
            }
        }
        throw new IllegalStateException("프로젝트 식별자 생성에 실패했습니다.");
    }
}
