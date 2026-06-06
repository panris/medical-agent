package com.medicalagent.agent;

import com.medicalagent.model.AgentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 状态图编排 (T2.6)
 * 手动实现 LangGraph conditional edge 等价逻辑
 * 节点: router → collect_info / retrieval / emergency / end
 *        retrieval → diagnose → end
 *        collect_info → router (循环)
 */
@Component
public class GraphOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(GraphOrchestrator.class);

    private final RouterAgent routerAgent;
    private final CollectInfoAgent collectInfoAgent;
    private final RetrievalAgent retrievalAgent;
    private final DiagnosisAgent diagnosisAgent;

    public GraphOrchestrator(RouterAgent routerAgent,
                             CollectInfoAgent collectInfoAgent,
                             RetrievalAgent retrievalAgent,
                             DiagnosisAgent diagnosisAgent) {
        this.routerAgent = routerAgent;
        this.collectInfoAgent = collectInfoAgent;
        this.retrievalAgent = retrievalAgent;
        this.diagnosisAgent = diagnosisAgent;
    }

    /**
     * 执行状态图的一步
     * @return 下一步的 Agent 响应消息
     */
    public StepResult executeStep(AgentState state) {
        String currentNode = state.getCurrentAgent();
        if (currentNode == null) currentNode = "router";

        log.debug("Executing node: {}", currentNode);

        return switch (currentNode) {
            case "router" -> executeRouter(state);
            case "collect_info" -> executeCollectInfo(state);
            case "retrieval" -> executeRetrieval(state);
            case "emergency" -> executeEmergency(state);
            case "diagnose" -> executeDiagnose(state);
            case "end" -> new StepResult("end", null, true);
            default -> {
                log.warn("Unknown node: {}, defaulting to router", currentNode);
                state.setCurrentAgent("router");
                yield executeRouter(state);
            }
        };
    }

    /**
     * 执行完整流程（自动推进直到结束或需要用户输入）
     */
    public List<StepResult> executeUntilBlock(AgentState state) {
        List<StepResult> results = new ArrayList<>();
        int maxSteps = 10; // 防止无限循环

        for (int i = 0; i < maxSteps; i++) {
            StepResult result = executeStep(state);
            results.add(result);

            if (result.finished || result.needsUserInput || result.isPendingExecution()) {
                break;
            }
        }

        if (results.size() >= maxSteps) {
            log.warn("Graph execution hit max steps limit ({})", maxSteps);
            state.setError("对话轮次过多，已终止当前会话");
        }

        return results;
    }

    private StepResult executeRouter(AgentState state) {
        String nextNode = routerAgent.route(state);
        routerAgent.postRoute(state, nextNode);
        // 路由本身不产生输出，直接推进到下一个节点
        return executeStep(state);
    }

    private StepResult executeCollectInfo(AgentState state) {
        String question = collectInfoAgent.collect(state);
        if (question == null) {
            // 信息收集完毕，回到路由判断下一步
            state.setCurrentAgent("router");
            // 防止 maxSteps 内多次 collect → router 死循环
            state.setTurnCount(state.getTurnCount() + 1);
            if (state.getTurnCount() > 5) {
                // 强制进入诊断
                state.setCurrentAgent("diagnose");
                return new StepResult("diagnose", null, false);
            }
            return executeStep(state);
        }
        return new StepResult("collect_info", question, false, true);
    }

    private StepResult executeRetrieval(AgentState state) {
        boolean confident = retrievalAgent.retrieve(state);
        if (confident) {
            state.setCurrentAgent("diagnose");
            return executeStep(state);
        } else {
            // 检索置信度不足，回退到信息采集
            state.setCurrentAgent("collect_info");
            String question = "您能再详细描述一下症状吗？比如具体位置、发作时间、伴随症状等。";
            return new StepResult("retrieval", question, false);
        }
    }

    private StepResult executeEmergency(AgentState state) {
        String message = "🚨 **检测到紧急情况**\n\n" +
                "请立即拨打 **120** 急救电话！\n\n" +
                "在等待救护车期间：\n" +
                "1. 保持患者平躺，注意保暖\n" +
                "2. 不要随意移动患者\n" +
                "3. 如患者意识清醒，安抚情绪\n" +
                "4. 如患者无意识，检查呼吸，必要时进行心肺复苏\n\n" +
                "【免责声明】以上为紧急建议，请以专业急救人员指导为准。";

        Map<String, Object> diagnosis = new LinkedHashMap<>();
        diagnosis.put("severity", "critical");
        diagnosis.put("recommendation", "立即拨打120");
        diagnosis.put("disclaimer", true);
        state.setDiagnosisResult(diagnosis);
        state.setNeedsMoreInfo(false);

        return new StepResult("emergency", message, true);
    }

    /**
     * diagnose 节点 — 只标记状态，不执行 LLM
     * 实际的 LLM 调用由 ChatController 决定（同步/流式）
     */
    private StepResult executeDiagnose(AgentState state) {
        // 标记当前节点为 diagnose，但不执行 LLM
        // Controller 会根据情况调用 DiagnosisAgent.diagnose() 或 diagnoseStreaming()
        return new StepResult("diagnose", null, false);
    }

    /**
     * 步骤执行结果
     */
    public static class StepResult {
        private final String node;
        private final String message;
        private final boolean finished;
        private final boolean needsUserInput;

        public StepResult(String node, String message, boolean finished, boolean needsUserInput) {
            this.node = node;
            this.message = message;
            this.finished = finished;
            this.needsUserInput = needsUserInput;
        }

        public StepResult(String node, String message, boolean finished) {
            this.node = node;
            this.message = message;
            this.finished = finished;
            // diagnose 节点 message 为 null 时标记为待执行（由 Controller 接管）
            this.needsUserInput = false;
        }

        /** 是否需要 Controller 接管执行（如流式 LLM 调用） */
        public boolean isPendingExecution() {
            return "diagnose".equals(node) && message == null && !finished;
        }

        public String getNode() { return node; }
        public String getMessage() { return message; }
        public boolean isFinished() { return finished; }
        public boolean isNeedsUserInput() { return needsUserInput; }
    }
}
