package com.orchestrix.controller;

import com.orchestrix.mode.ModeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class RootController {

    private final ModeService modeService;

    @GetMapping("/")
    public ResponseEntity<Map<String, Object>> root() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "orchestrix");
        body.put("status", "ok");
        body.put("mode", modeService.current().name());
        body.put("endpoints", Map.of(
                "chat",       "POST /v1/chat/completions",
                "tenant",     "GET  /tenant/state",
                "mode",       "GET  /admin/mode  |  POST /admin/mode/switch  body={\"mode\":\"MOCK|REAL|HYBRID\"}",
                "providers",  "GET  /admin/provider/status",
                "failure",    "POST /admin/providers/{name}/fail",
                "metrics",    "GET  /actuator/prometheus"));
        return ResponseEntity.ok(body);
    }
}
