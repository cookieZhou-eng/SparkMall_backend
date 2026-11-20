package com.example.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/** 工具定义的函数部分(名称/描述/JSON Schema 参数) */
@Data
public class LlmFuncDef {
    private String name;
    private String description;
    /** JSON Schema 参数定义(原样透传给模型) */
    private JsonNode parameters;
}
