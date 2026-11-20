package com.example.ai.llm;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * OpenAI 兼容 /chat/completions 协议的消息体。
 * role: system / user / assistant / tool;
 * assistant 消息可携带 tool_calls(发起工具调用);
 * tool 消息用 tool_call_id 关联对应的调用结果。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LlmMessage {
    private String role;
    /** 文本内容(assistant 发起工具调用时可为 null) */
    private String content;
    /** assistant 发起的工具调用列表(协议字段 tool_calls) */
    @JsonProperty("tool_calls")
    private List<LlmToolCall> toolCalls;
    /** tool 消息: 对应的调用 id */
    @JsonProperty("tool_call_id")
    private String toolCallId;
    /** tool 消息: 工具名(部分网关校验用) */
    private String name;

    /** 快捷构造 system/user 等纯文本消息 */
    public static LlmMessage of(String role, String content) {
        LlmMessage m = new LlmMessage();
        m.setRole(role);
        m.setContent(content);
        return m;
    }
}
