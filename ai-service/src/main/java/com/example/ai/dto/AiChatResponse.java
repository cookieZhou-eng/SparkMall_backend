package com.example.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** POST /ai/chat 响应体 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiChatResponse {
    /** 会话业务号(前端下次携带以延续上下文) */
    private String conversationNo;
    /** 助手回复文本 */
    private String reply;
}
