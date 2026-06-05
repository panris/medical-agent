package com.medicalagent.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查 + TTFT 监控 (T5.3)
 */
@RestController
@RequestMapping("/api/v1")
public class HealthController {

    /**
     * 应用健康状态
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "UP");
        status.put("timestamp", System.currentTimeMillis());
        status.put("jvm_uptime_ms", ManagementFactory.getRuntimeMXBean().getUptime());
        return status;
    }
}
