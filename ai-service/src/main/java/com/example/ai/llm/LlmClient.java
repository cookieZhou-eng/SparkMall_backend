package com.example.ai.llm;

import com.example.ai.config.AiProperties;
import com.example.ai.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * LLM 客户端封装: 调用 OpenAI 兼容的 /chat/completions 接口(默认 DeepSeek)。
 *
 * 实现说明:
 *  - 用 JDK 自带 java.net.http.HttpClient 直发 HTTP, 不引入额外 SDK;
 *    也不走 Spring RestClient, 规避 Spring Framework 7 的 factory API 变动。
 *  - 每次调用按 AiProperties 动态取配置, Nacos 改 key/model 后无需重启生效
 *    (配合配置刷新)。
 */
@Component
public class LlmClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final AiProperties props;
    private final ObjectMapper objectMapper;

    public LlmClient(AiProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
    }

    /**
     * 发起一次对话补全。
     * @param messages 完整消息列表(system/历史/本轮输入/工具结果)
     * @param tools    工具定义; null 表示不带工具(用于轮次耗尽后强制出文本)
     * @return 模型返回的消息(纯文本 或 携带 toolCalls)
     */
    public LlmMessage chat(List<LlmMessage> messages, List<LlmToolDef> tools) {
        AiProperties.Llm cfg = props.getLlm();
        if (cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
            throw new BusinessException(500, "未配置 LLM_API_KEY, 请在环境变量或 Nacos 配置中设置");
        }
        try {
            LlmChatRequest body = new LlmChatRequest();
            body.setModel(cfg.getModel());
            body.setMessages(messages);
            body.setTools(tools);
            body.setTemperature(cfg.getTemperature());
            body.setMaxTokens(cfg.getMaxTokens());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(cfg.getBaseUrl() + "/chat/completions"))
                    .timeout(Duration.ofSeconds(cfg.getTimeoutSeconds() == null ? 60 : cfg.getTimeoutSeconds()))
                    .header("Authorization", "Bearer " + cfg.getApiKey())
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new BusinessException(500, "模型调用失败: HTTP " + response.statusCode());
            }
            LlmChatResponse resp = objectMapper.readValue(response.body(), LlmChatResponse.class);
            if (resp.getChoices() == null || resp.getChoices().isEmpty()) {
                throw new BusinessException(500, "模型返回为空");
            }
            LlmMessage message = resp.getChoices().get(0).getMessage();
            // 兜底: 个别网关可能不回 tool_call id, 生成一个保证 tool 消息能关联
            if (message.getToolCalls() != null) {
                for (int i = 0; i < message.getToolCalls().size(); i++) {
                    LlmToolCall call = message.getToolCalls().get(i);
                    if (call.getId() == null || call.getId().isBlank()) {
                        call.setId("call_" + i);
                    }
                }
            }
            return message;
        } catch (BusinessException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(500, "模型调用被中断");
        } catch (Exception e) {
            throw new BusinessException(500, "模型调用失败: " + e.getMessage());
        }
    }
}
