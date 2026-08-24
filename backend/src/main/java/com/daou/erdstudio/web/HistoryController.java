package com.daou.erdstudio.web;

import com.daou.erdstudio.service.HistoryService;
import com.daou.erdstudio.service.RoomService;
import com.daou.erdstudio.web.dto.HistoryDiff;
import com.daou.erdstudio.web.dto.HistoryEntry;
import com.daou.erdstudio.web.dto.Op;
import com.daou.erdstudio.web.dto.SchemaDoc;
import com.daou.erdstudio.ws.OpBroadcaster;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 방 단위 변경 이력 조회/복원 API. */
@RestController
@RequestMapping("/api/rooms/{roomId}/history")
public class HistoryController {

    private final HistoryService historyService;
    private final RoomService roomService;
    private final OpBroadcaster opBroadcaster;
    private final ObjectMapper objectMapper;

    public HistoryController(HistoryService historyService, RoomService roomService,
                             OpBroadcaster opBroadcaster, ObjectMapper objectMapper) {
        this.historyService = historyService;
        this.roomService = roomService;
        this.opBroadcaster = opBroadcaster;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public List<HistoryEntry> list(@PathVariable Long roomId,
                                   @RequestParam(defaultValue = "50") int limit) {
        roomService.requireExists(roomId);
        return historyService.list(roomId, limit);
    }

    /** 선택 이력과 직전 이력의 스냅샷 쌍 — 프론트가 받아서 변경점을 계산해 보여준다. */
    @GetMapping("/{id}/diff")
    public HistoryDiff diff(@PathVariable Long roomId, @PathVariable long id) {
        roomService.requireExists(roomId);
        return historyService.diff(roomId, id);
    }

    /** 스냅샷 복원 후 schema.replace op로 그 방의 전원에게 새 상태를 브로드캐스트한다. */
    @PostMapping("/{id}/restore")
    public Map<String, Object> restore(@PathVariable Long roomId, @PathVariable long id,
                                       @RequestHeader(value = "X-User", defaultValue = "unknown") String rawUser) {
        roomService.requireExists(roomId);
        String user = UserHeader.decode(rawUser);
        SchemaDoc doc = historyService.restore(roomId, id, user);
        Op op = new Op("schema.replace", user,
                objectMapper.createObjectNode().set("doc", objectMapper.valueToTree(doc)));
        opBroadcaster.broadcastOp(roomId, op);
        return Map.of("ok", true, "restored", id);
    }
}
