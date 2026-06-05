package com.medicalagent.config;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.DisabledChatModel;
import dev.langchain4j.model.output.Response;

import java.util.ArrayList;
import java.util.List;

/**
 * Fallback 模型包装器 (T3.2)
 * 依次尝试多个模型，第一个成功即返回结果
 */
public class FallbackChatModel implements ChatLanguageModel {

    private final ChatLanguageModel primary;
    private final ChatLanguageModel fallback;

    public FallbackChatModel(ChatLanguageModel primary, ChatLanguageModel fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        // 尝试主模型
        if (!(primary instanceof DisabledChatModel)) {
            try {
                return primary.generate(messages);
            } catch (Exception e) {
                System.err.println("[ModelFactory] Primary model failed: " + e.getMessage());
            }
        }

        // 主模型失败，尝试 fallback
        if (!(fallback instanceof DisabledChatModel)) {
            try {
                return fallback.generate(messages);
            } catch (Exception e) {
                System.err.println("[ModelFactory] Fallback model failed: " + e.getMessage());
            }
        }

        // 全部失败，返回兜底响应
        return new Response<>(AiMessage.from("抱歉，当前服务暂时不可用，请稍后再试。"));
    }

    @Override
    public Response<AiMessage> generate(UserMessage userMessage) {
        return generate(List.of(userMessage));
    }

    @Override
    public Response<AiMessage> generate(SystemMessage systemMessage, UserMessage userMessage) {
        return generate(List.of(systemMessage, userMessage));
    }
}
