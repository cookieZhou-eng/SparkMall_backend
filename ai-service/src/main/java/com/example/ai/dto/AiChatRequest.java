package com.example.ai.dto;

import lombok.Data;

/** POST /ai/chat 请求体 */
@Data
public class AiChatRequest {
    /** 会话业务号(可空, 空则新建会话) */
    private String conversationNo;
    /** 用户本轮输入 */
    private String message;
}
