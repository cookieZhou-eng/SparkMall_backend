package com.example.ai.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/** /chat/completions 响应体(只取用到的字段) */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class LlmChatResponse {
    private String id;
    private List<LlmChoice> choices;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LlmChoice {
        private Integer index;
        private LlmMessage message;
        /** finish_reason: stop-正常文本 / tool_calls-请求工具调用 */
        @JsonProperty("finish_reason")
        private String finishReason;
    }
}
