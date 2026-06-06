package com.medicalagent.config;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;

import java.util.List;

/**
 * Fallback 模型包装器 (T3.2)
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
        if (primary != null) {
            try {
                return primary.generate(messages);
            } catch (Exception e) {
                System.err.println("[Fallback] Primary failed: " + e.getMessage());
            }
        }

        if (fallback != null) {
            try {
                return fallback.generate(messages);
            } catch (Exception e) {
                System.err.println("[Fallback] Secondary failed: " + e.getMessage());
            }
        }

        return new Response<>(AiMessage.from("抱歉，当前服务暂时不可用，请稍后再试。"));
    }
}
