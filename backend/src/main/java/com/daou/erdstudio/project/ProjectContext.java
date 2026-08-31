package com.daou.erdstudio.project;

/**
 * 요청 스레드의 현재 프로젝트 id 를 담는 ThreadLocal.
 * 인터셉터가 X-Project-Id 헤더에서 설정하고 요청 종료 시 제거한다.
 * 비동기 콜백은 다른 스레드에서 실행되므로 {@link #runWith}로 명시 전파한다.
 */
public final class ProjectContext {

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private ProjectContext() {
    }

    public static void set(Long projectId) {
        CURRENT.set(projectId);
    }

    public static Long getProjectId() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** 지정 프로젝트 컨텍스트로 작업을 실행하고 원복한다(off-thread 콜백용). */
    public static void runWith(Long projectId, Runnable action) {
        Long prev = CURRENT.get();
        CURRENT.set(projectId);
        try {
            action.run();
        } finally {
            if (prev != null) {
                CURRENT.set(prev);
            } else {
                CURRENT.remove();
            }
        }
    }
}
