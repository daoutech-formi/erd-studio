package com.daou.erdstudio.web;

import com.daou.erdstudio.service.HistoryService;
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

@RestController
@RequestMapping("/api/history")
public class HistoryController {

    private final HistoryService historyService;
    private final OpBroadcaster opBroadcaster;
    private final ObjectMapper objectMapper;

    public HistoryController(HistoryService historyService, OpBroadcaster opBroadcaster,
                             ObjectMapper objectMapper) {
        this.historyService = historyService;
        this.opBroadcaster = opBroadcaster;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public List<HistoryEntry> list(@RequestParam(defaultValue = "50") int limit) {
        return historyService.list(limit);
    }

    /** 스냅샷 복원 후 schema.replace op로 전원에게 새 상태를 브로드캐스트한다. */
    @PostMapping("/{id}/restore")
    public Map<String, Object> restore(@PathVariable long id,
                                       @RequestHeader(value = "X-User", defaultValue = "unknown") String user) {
        SchemaDoc doc = historyService.restore(id, user);
        Op op = new Op("schema.replace", user,
                objectMapper.createObjectNode().set("doc", objectMapper.valueToTree(doc)));
        opBroadcaster.broadcastOp(op);
        return Map.of("ok", true, "restored", id);
    }
}
