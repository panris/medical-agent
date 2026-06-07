package com.medicalagent.agent;

import com.medicalagent.config.ModelFactory;
import com.medicalagent.model.AgentState;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 兜底闲聊 Agent
 * 当用户输入不属于医疗问诊意图（非症状描述、非紧急、非告别）时，
 * 以友好但克制的方式回应，并引导回医疗问诊流程。
 */
@Component
public class CasualAgent {

    private static final Logger log = LoggerFactory.getLogger(CasualAgent.class);

    private static final String SYSTEM_PROMPT = """
            你是一个医疗问诊助手的闲聊兜底模块。用户的输入不属于任何医疗问诊意图，
            你的职责是：
            1. 简短友好地回应用户（不超过2句话）
            2. 自然地引导用户回到医疗问诊——询问是否哪里不舒服，或提醒你可以帮助分析症状
            3. 不要假装能回答非医疗问题
            4. 不要提及自己是 AI 或大模型

            示例：
            用户说"今天天气不错" → "是呢！您今天身体怎么样，有什么不舒服的地方吗？"
            用户说"你是谁" → "我是您的健康问诊助手，有什么症状想咨询吗？"
            用户说"1+1等于几" → "这个问题超出了我的能力范围哦。如果您或家人有任何健康方面的疑问，随时可以问我。"
            """;

    // 纯闲聊正则（完全无医疗相关性的输入）
    private static final java.util.regex.Pattern SMALL_TALK = java.util.regex.Pattern.compile(
            "^(天气|你是谁|你叫什么|你好吗|在吗|哈哈|呵呵|无聊|谢谢|OK|ok|好的|嗯嗯|嗯|噢|哦|啊啊啊)" +
            "|(今天.*怎么样|最近怎么样|吃饭了吗|睡了吗|早安|晚安|加油|辛苦了)",
            java.util.regex.Pattern.CASE_INSENSITIVE
    );

    private final ModelFactory modelFactory;

    public CasualAgent(ModelFactory modelFactory) {
        this.modelFactory = modelFactory;
    }

    /**
     * 处理非医疗意图的用户输入
     * @return 友好回应 + 引导回问诊
     */
    public String chat(AgentState state) {
        String lastMessage = getLastUserMessage(state);
        if (lastMessage == null || lastMessage.isBlank()) {
            return "您好！请问有什么健康方面的问题需要咨询吗？";
        }

        // 纯闲聊直接用模板回复，省一次 LLM 调用
        if (SMALL_TALK.matcher(lastMessage).find()) {
            return pickTemplate();
        }

        // 尝试 LLM 回复
        ChatLanguageModel model = modelFactory.getDefaultModel();
        if (model == null) {
            return pickTemplate();
        }

        try {
            AiMessage response = model.generate(
                    SystemMessage.from(SYSTEM_PROMPT),
                    UserMessage.from(lastMessage)
            ).content();
            return response.text().trim();
        } catch (Exception e) {
            log.warn("CasualAgent LLM 调用失败: {}", e.getMessage());
            return pickTemplate();
        }
    }

    private String pickTemplate() {
        String[] templates = {
            "如果您有任何健康方面的疑问，随时可以告诉我，我来帮您分析。",
            "我主要擅长健康问诊，您有什么不舒服的地方吗？",
            "有什么症状想咨询吗？描述得越详细，我的建议越准确。",
        };
        return templates[(int) (System.currentTimeMillis() % templates.length)];
    }

    private String getLastUserMessage(AgentState state) {
        List<AgentState.Message> messages = state.getMessages();
        if (messages == null || messages.isEmpty()) return null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            AgentState.Message msg = messages.get(i);
            if ("user".equals(msg.getType())) {
                return msg.getContent();
            }
        }
        return null;
    }
}
