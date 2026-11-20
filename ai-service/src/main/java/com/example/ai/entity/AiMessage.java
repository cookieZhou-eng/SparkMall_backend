package com.example.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * AI 助手消息实体(对应 t_ai_message 表)。
 * role 分三类: user-用户输入 / assistant-助手回复 / tool-工具返回。
 * tool 消息记录工具名、参数与原始返回, 便于答辩时回放 Agent 的完整决策链路。
 */
@Data
@NoArgsConstructor
@TableName("t_ai_message")
public class AiMessage {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 会话ID(t_ai_conversation.id) */
    private Long conversationId;
    /** 消息角色: user / assistant / tool */
    private String role;
    /** 消息文本内容(用户输入或助手回复; tool 消息此字段为空) */
    private String content;
    /** 工具名(role=tool 时记录, 如 searchProducts) */
    private String toolName;
    /** 工具入参 JSON(role=tool 时记录) */
    private String toolArgs;
    /** 工具原始返回(role=tool 时记录) */
    private String toolResult;
    @TableLogic
    private Integer isDeleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
