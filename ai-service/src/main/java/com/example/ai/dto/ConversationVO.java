package com.example.ai.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 会话列表项(GET /ai/conversations) */
@Data
public class ConversationVO {
    private Long id;
    private String conversationNo;
    private String title;
    private Integer messageCount;
    private LocalDateTime lastMessageTime;

    public ConversationVO(com.example.ai.entity.AiConversation c) {
        this.id = c.getId();
        this.conversationNo = c.getConversationNo();
        this.title = c.getTitle();
        this.messageCount = c.getMessageCount();
        this.lastMessageTime = c.getLastMessageTime();
    }
}
