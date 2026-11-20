package com.example.ai.llm;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/** /chat/completions 请求体(OpenAI 兼容) */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LlmChatRequest {
    private String model;
    private List<LlmMessage> messages;
    /** 工具定义(纯文本回复时不传) */
    private List<LlmToolDef> tools;
    private Double temperature;
    @JsonProperty("max_tokens")
    private Integer maxTokens;
}
