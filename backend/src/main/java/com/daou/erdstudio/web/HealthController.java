package com.daou.erdstudio.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 헬스 체크 — 방과 무관하게 항상 /api/health 로 유지한다. */
@RestController
@RequestMapping("/api")
public class HealthController {

    @GetMapping("/health")
    public Map<String, Boolean> health() {
        return Map.of("ok", true);
    }
}
