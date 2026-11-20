package com.example.ai.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/** 工具调用的函数部分: name + arguments(注意 arguments 是 JSON 字符串, 不是对象) */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class LlmFunctionCall {
    private String name;
    private String arguments;
}
