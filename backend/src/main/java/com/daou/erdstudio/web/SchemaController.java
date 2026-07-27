package com.daou.erdstudio.web;

import com.daou.erdstudio.service.SchemaService;
import com.daou.erdstudio.web.dto.SchemaDoc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class SchemaController {

    private final SchemaService schemaService;

    public SchemaController(SchemaService schemaService) {
        this.schemaService = schemaService;
    }

    @GetMapping("/health")
    public Map<String, Boolean> health() {
        return Map.of("ok", true);
    }

    @GetMapping("/schema")
    public SchemaDoc schema() {
        return schemaService.loadDoc();
    }
}
