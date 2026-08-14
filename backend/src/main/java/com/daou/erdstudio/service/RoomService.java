package com.daou.erdstudio.service;

import com.daou.erdstudio.domain.ErdRoom;
import com.daou.erdstudio.repository.ErdHistoryRepository;
import com.daou.erdstudio.repository.ErdRoomRepository;
import com.daou.erdstudio.web.dto.SchemaDoc;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** ERD 방 생성·조회·삭제. 방 개수 상한과 이름 규칙을 여기서 강제한다. */
@Service
public class RoomService {

    /** 서버 전체에서 만들 수 있는 방의 최대 개수. */
    private static final int MAX_ROOMS = 20;
    private static final int MAX_NAME_LENGTH = 30;
    private static final String DEFAULT_DOMAIN_KEY = "etc";

    private final ErdRoomRepository roomRepository;
    private final ErdHistoryRepository historyRepository;
    private final SchemaService schemaService;

    public RoomService(ErdRoomRepository roomRepository, ErdHistoryRepository historyRepository,
                       SchemaService schemaService) {
        this.roomRepository = roomRepository;
        this.historyRepository = historyRepository;
        this.schemaService = schemaService;
    }

    @Transactional(readOnly = true)
    public List<ErdRoom> list() {
        return roomRepository.findAllByOrderByIdAsc();
    }

    /**
     * 방을 만든다. 이름은 공백 불가·중복 불가이고 전체 방은 {@value #MAX_ROOMS}개를 넘을 수 없다.
     * 빈 방에서도 바로 테이블을 추가할 수 있도록 기본 도메인('기타') 하나를 함께 만든다.
     */
    @Transactional
    public ErdRoom create(String name, String user) {
        return create(name, user, null);
    }

    @Transactional
    public ErdRoom create(String name, String user, String clientKey) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("방 이름을 입력하세요.");
        }
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("방 이름은 " + MAX_NAME_LENGTH + "자 이내로 입력하세요.");
        }
        if (roomRepository.existsByName(trimmed)) {
            throw new IllegalArgumentException("이미 존재하는 방 이름입니다.");
        }
        if (roomRepository.count() >= MAX_ROOMS) {
            throw new IllegalArgumentException("방은 최대 " + MAX_ROOMS + "개까지 만들 수 있습니다.");
        }
        ErdRoom room = roomRepository.save(new ErdRoom(trimmed, safeUser(user), safeClientKey(clientKey)));
        schemaService.replaceAll(room.getId(), defaultDoc());
        return room;
    }

    /** 방과 그 방의 이력·관계·컬럼·테이블·도메인을 모두 지운다. */
    @Transactional
    public void delete(Long roomId) {
        requireExists(roomId);
        historyRepository.deleteByRoomId(roomId);
        schemaService.deleteAll(roomId);
        roomRepository.deleteById(roomId);
    }

    /** 방이 없으면 IllegalArgumentException — 컨트롤러/WebSocket 진입점에서 먼저 호출한다. */
    @Transactional(readOnly = true)
    public void requireExists(Long roomId) {
        if (roomId == null || !roomRepository.existsById(roomId)) {
            throw new IllegalArgumentException("존재하지 않는 방입니다.");
        }
    }

    private SchemaDoc defaultDoc() {
        Map<String, SchemaDoc.DomainDef> domains = new LinkedHashMap<>();
        domains.put(DEFAULT_DOMAIN_KEY, new SchemaDoc.DomainDef("기타", "#9aa0aa"));
        return new SchemaDoc(domains, List.of(), List.of(), Map.of(), List.of());
    }

    /** 사용자 이름 변경 — 같은 브라우저(clientKey)로 만든 방들의 생성자 표시명을 갱신한다. */
    @Transactional
    public int renameCreator(String clientKey, String newName) {
        String key = safeClientKey(clientKey);
        if (key == null) {
            throw new IllegalArgumentException("브라우저 식별자가 올바르지 않습니다.");
        }
        String name = safeUser(newName);
        List<ErdRoom> rooms = roomRepository.findByCreatorClientKey(key);
        rooms.forEach(room -> room.updateCreatedBy(name));
        return rooms.size();
    }

    private String safeUser(String user) {
        if (user == null || user.isBlank()) {
            return "unknown";
        }
        return user.length() > 20 ? user.substring(0, 20) : user;
    }

    private String safeClientKey(String clientKey) {
        if (clientKey == null || clientKey.isBlank() || clientKey.length() > 64) {
            return null;
        }
        return clientKey;
    }
}
