package com.example.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 助手配置(对应 application.yaml 里的 app.ai 段)。
 * 支持 Nacos 动态刷新与环境变量占位符, 如 LLM_API_KEY / LLM_BASE_URL / LLM_MODEL。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.ai")
public class AiProperties {

    /** 大模型(OpenAI 兼容 /chat/completions 协议) */
    private Llm llm = new Llm();
    /** 语料分块检索(RAG) */
    private Rag rag = new Rag();
    /** 系统提示词资源路径 */
    private String promptPath;
    /** 工具定义(OpenAI tools 协议 JSON)资源路径 */
    private String toolsPath;
    /** 模型调用失败时的兜底配置 */
    private Fallback fallback = new Fallback();

    @Data
    public static class Llm {
        private String baseUrl;
        private String apiKey;
        private String model;
        private Double temperature;
        private Integer maxTokens;
        private Integer timeoutSeconds;
        private Integer maxToolRounds;
        private Integer historyRounds;
    }

    @Data
    public static class Rag {
        private Integer topK;
        private Double similarityThreshold;
        private String corpusPath;
    }

    @Data
    public static class Fallback {
        private String errorReply;
    }
}
