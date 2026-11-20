package com.example.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * AI 助手会话实体(对应 t_ai_conversation 表)。
 * conversation_no 为业务键(UUID, 对外暴露), 自增 id 仅作主键。
 */
@Data
@NoArgsConstructor
@TableName("t_ai_conversation")
public class AiConversation {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 会话业务号(UUID, 对外暴露) */
    private String conversationNo;
    /** 所属用户ID */
    private Long userId;
    /** 会话标题(默认取首条用户消息前20字) */
    private String title;
    /** 消息条数(冗余计数) */
    private Integer messageCount;
    /** 最后一条消息时间(会话列表排序) */
    private LocalDateTime lastMessageTime;
    @TableLogic
    private Integer isDeleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
