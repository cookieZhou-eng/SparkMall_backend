package com.example.ai.dto;

import com.example.ai.entity.AiMessage;
import lombok.Data;

import java.time.LocalDateTime;

/** 会话消息项(GET /ai/conversations/{conversationNo}/messages) */
@Data
public class AiMessageVO {
    private Long id;
    private String role;
    /** 消息文本(role=tool 时无文本, 给出工具调用提示) */
    private String content;
    private String toolName;
    private LocalDateTime createTime;

    public AiMessageVO(AiMessage m) {
        this.id = m.getId();
        this.role = m.getRole();
        this.content = ("tool".equals(m.getRole()) && (m.getContent() == null || m.getContent().isBlank()))
                ? "[工具调用] " + m.getToolName()
                : m.getContent();
        this.toolName = m.getToolName();
        this.createTime = m.getCreateTime();
    }
}
