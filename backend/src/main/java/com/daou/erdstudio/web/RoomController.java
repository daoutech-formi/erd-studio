package com.daou.erdstudio.web;

import com.daou.erdstudio.domain.ErdRoom;
import com.daou.erdstudio.project.ProjectService;
import com.daou.erdstudio.repository.ErdTableRepository;
import com.daou.erdstudio.service.RoomService;
import com.daou.erdstudio.ws.SessionRegistry;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** ERD 방 목록/생성/삭제 API. 접속자 수는 WebSocket 세션 기준(브라우저 단위)이다. */
@RestController
@RequestMapping("/api/rooms")
public class RoomController {

    /** 방 목록 응답 행. projectSlug 는 딥링크가 프로젝트를 넘나들 때 선택 전환용으로 쓴다. */
    public record RoomInfo(Long id, String name, String createdBy, Instant createdAt,
                           long tableCount, int userCount, String projectSlug) {
    }

    public record CreateRoomRequest(String name, String clientKey) {
    }

    public record RenameCreatorRequest(String clientKey, String name) {
    }

    private final RoomService roomService;
    private final ErdTableRepository tableRepository;
    private final SessionRegistry sessions;
    private final ProjectService projectService;

    public RoomController(RoomService roomService, ErdTableRepository tableRepository,
                          SessionRegistry sessions, ProjectService projectService) {
        this.roomService = roomService;
        this.tableRepository = tableRepository;
        this.sessions = sessions;
        this.projectService = projectService;
    }

    @GetMapping
    public List<RoomInfo> list() {
        List<RoomInfo> rooms = new ArrayList<>();
        for (ErdRoom room : roomService.list()) {
            rooms.add(toInfo(room));
        }
        return rooms;
    }

    /** 딥링크(#/room/:id) 진입용 단건 조회 — 프로젝트와 무관하게 찾는다. */
    @GetMapping("/{roomId}")
    public RoomInfo get(@PathVariable Long roomId) {
        return toInfo(roomService.get(roomId));
    }

    @PostMapping
    public RoomInfo create(@RequestBody CreateRoomRequest req,
                           @RequestHeader(value = "X-User", defaultValue = "unknown") String user) {
        return toInfo(roomService.create(req.name(), UserHeader.decode(user), req.clientKey()));
    }

    /** 이름 변경 — 같은 브라우저(clientKey)로 만든 방들의 생성자 표시명을 새 이름으로 바꾼다. */
    @PutMapping("/creator-name")
    public Map<String, Object> renameCreator(@RequestBody RenameCreatorRequest req) {
        int updated = roomService.renameCreator(req.clientKey(), req.name());
        return Map.of("ok", true, "updated", updated);
    }

    @DeleteMapping("/{roomId}")
    public Map<String, Object> delete(@PathVariable Long roomId) {
        roomService.delete(roomId);
        return Map.of("ok", true);
    }

    private RoomInfo toInfo(ErdRoom room) {
        return new RoomInfo(room.getId(), room.getName(), room.getCreatedBy(), room.getCreatedAt(),
                tableRepository.countByRoomId(room.getId()), sessions.userCount(room.getId()),
                projectService.slugOf(room.getProjectId()));
    }
}
