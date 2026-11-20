package com.example.ai.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/** 模型返回的单个工具调用: id + 函数名 + JSON 字符串参数 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class LlmToolCall {
    private String id;
    private String type;
    private LlmFunctionCall function;
}
