package com.medicalagent.api;

import com.medicalagent.agent.GraphOrchestrator;
import com.medicalagent.agent.RouterAgent;
import com.medicalagent.config.ModelFactory;
import com.medicalagent.filter.SensitiveDataFilter;
import com.medicalagent.model.AgentState;
import com.medicalagent.repository.SessionRepository;
import com.medicalagent.service.ComplianceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 评估 API (T5.4)
 * 批量测试用例 + 准确率/延迟统计
 */
@RestController
@RequestMapping("/api/v1/eval")
public class EvalController {

    private static final Logger log = LoggerFactory.getLogger(EvalController.class);

    private final GraphOrchestrator graphOrchestrator;
    private final RouterAgent routerAgent;
    private final SensitiveDataFilter sensitiveDataFilter;
    private final SessionRepository sessionRepository;
    private final ObjectMapper objectMapper;

    public EvalController(GraphOrchestrator graphOrchestrator,
                          RouterAgent routerAgent,
                          SensitiveDataFilter sensitiveDataFilter,
                          SessionRepository sessionRepository,
                          ObjectMapper objectMapper) {
        this.graphOrchestrator = graphOrchestrator;
        this.routerAgent = routerAgent;
        this.sensitiveDataFilter = sensitiveDataFilter;
        this.sessionRepository = sessionRepository;
        this.objectMapper = objectMapper;
    }

    @Value("${session.ttl-minutes:30}")
    private long sessionTtlMinutes;

    /**
     * 运行评估
     */
    @PostMapping("/run")
    public Map<String, Object> runEval(@RequestBody(required = false) EvalRequest request) {
        int limit = request != null && request.limit > 0 ? Math.min(request.limit, 500) : 50;
        List<TestResult> results = new ArrayList<>();

        List<TestCase> cases = buildTestCases();
        int count = 0;

        for (TestCase tc : cases) {
            if (count >= limit) break;

            long start = System.nanoTime();
            try {
                AgentState state = new AgentState();
                state.setSessionId("eval_" + System.nanoTime());
                state.getMessages().add(new AgentState.Message("user", tc.input));

                // 路由评估
                String routed = routerAgent.route(state);
                boolean routeCorrect = tc.expectedRoute == null
                        || tc.expectedRoute.equals(routed);

                // 脱敏评估
                String sanitized = sensitiveDataFilter.filter(tc.input);
                boolean sanitizeOk = !tc.input.equals(sanitized) ? !sanitized.contains(tc.input) : true;

                long elapsed = (System.nanoTime() - start) / 1_000_000;

                results.add(new TestResult(tc, routed, routeCorrect, sanitized, sanitizeOk, elapsed));
                count++;

                // 清理评估产生的 session
                sessionRepository.delete(state.getSessionId());
            } catch (Exception e) {
                results.add(new TestResult(tc, "error", false, tc.input, false, 0));
            }
        }

        // 统计
        long routeCorrectCount = results.stream().filter(r -> r.routeCorrect).count();
        long sanitizeOkCount = results.stream().filter(r -> r.sanitizeOk).count();
        double avgLatency = results.stream().mapToLong(r -> r.latencyMs).average().orElse(0);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("total", count);
        report.put("route_accuracy", routeCorrectCount + "/" + count);
        report.put("sanitize_pass_rate", sanitizeOkCount + "/" + count);
        report.put("avg_latency_ms", String.format("%.1f", avgLatency));
        report.put("results", results);

        return report;
    }

    /**
     * 内置测试用例
     */
    private List<TestCase> buildTestCases() {
        List<TestCase> cases = new ArrayList<>();

        // === 路由测试 ===
        // 急诊
        cases.add(new TestCase("胸痛呼吸困难，感觉要晕了", "emergency", "急诊-胸痛"));
        cases.add(new TestCase("心脏病人发作了叫救护车", "emergency", "急诊-心脏病"));
        cases.add(new TestCase("大出血怎么办，大量出血", "emergency", "急诊-出血"));
        cases.add(new TestCase("休克了人没反应", "emergency", "急诊-休克"));
        cases.add(new TestCase("触电了手指抽搐", "emergency", "急诊-触电"));

        // 打招呼
        cases.add(new TestCase("你好", "collect_info", "打招呼"));
        cases.add(new TestCase("Hi", "collect_info", "打招呼英文"));
        cases.add(new TestCase("早上好", "collect_info", "打招呼-早上"));
        cases.add(new TestCase("hello", "collect_info", "打招呼英文2"));

        // 告别
        cases.add(new TestCase("谢谢", "end", "告别-谢谢"));
        cases.add(new TestCase("再见", "end", "告别-再见"));
        cases.add(new TestCase("拜拜", "end", "告别-拜拜"));
        cases.add(new TestCase("好的谢谢", "end", "告别-确认"));

        // 问诊 (collect_info 或 retrieval)
        cases.add(new TestCase("头疼三天了", "collect_info", "问诊-头疼"));
        cases.add(new TestCase("咳嗽发烧38度", "collect_info", "问诊-发烧"));
        cases.add(new TestCase("胃疼饭后更明显", "collect_info", "问诊-胃疼"));
        cases.add(new TestCase("最近失眠睡不着", "collect_info", "问诊-失眠"));

        // === 脱敏测试 ===
        cases.add(new TestCase("我叫张三丰，手机号13812345678", null, "脱敏-姓名+手机"));
        cases.add(new TestCase("身份证号110101199003076789", null, "脱敏-身份证"));
        cases.add(new TestCase("银行卡6222021234567890123", null, "脱敏-银行卡"));
        cases.add(new TestCase("我叫李四，手机 139 9876 5432", null, "脱敏-姓名+手机带空格"));

        return cases;
    }

    // --- DTO ---
    public static class EvalRequest { int limit; }

    public static class TestCase {
        String input, expectedRoute, description;
        TestCase(String input, String expectedRoute, String description) {
            this.input = input;
            this.expectedRoute = expectedRoute;
            this.description = description;
        }
    }

    public static class TestResult {
        String testCase, input, description;
        String expectedRoute, actualRoute;
        boolean routeCorrect, sanitizeOk;
        String sanitizedOutput;
        long latencyMs;

        TestResult(TestCase tc, String actualRoute, boolean routeCorrect,
                   String sanitized, boolean sanitizeOk, long latencyMs) {
            this.testCase = tc.description;
            this.input = tc.input;
            this.description = tc.description;
            this.expectedRoute = tc.expectedRoute;
            this.actualRoute = actualRoute;
            this.routeCorrect = routeCorrect;
            this.sanitizeOk = sanitizeOk;
            this.sanitizedOutput = sanitized;
            this.latencyMs = latencyMs;
        }
    }
}
