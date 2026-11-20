package com.example.ai.llm;

import lombok.Data;

/** 发给模型的工具定义(type=function + function{name,description,parameters}) */
@Data
public class LlmToolDef {
    private String type;
    private LlmFuncDef function;
}
