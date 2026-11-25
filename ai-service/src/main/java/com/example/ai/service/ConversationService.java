package com.example.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.ai.entity.AiConversation;
import com.example.ai.entity.AiMessage;
import com.example.ai.exception.BusinessException;
import com.example.ai.mapper.AiConversationMapper;
import com.example.ai.mapper.AiMessageMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 会话与消息持久化服务。
 * 会话以 conversation_no(UUID) 为业务键对外暴露, 自增 id 仅内部关联用。
 */
@Service
public class ConversationService {

    /** 会话历史单次最多回放的消息条数(防刷屏) */
    private static final int MAX_HISTORY_MESSAGES = 200;

    @Resource
    private AiConversationMapper conversationMapper;
    @Resource
    private AiMessageMapper messageMapper;

    /**
     * 取会话: conversationNo 为空则新建(UUID); 非空则校验归属, 不存在或非本人会话报 404。
     */
    public AiConversation createIfAbsent(Long userId, String conversationNo) {
        if (conversationNo != null && !conversationNo.isBlank()) {
            AiConversation exist = conversationMapper.selectOne(
                    new LambdaQueryWrapper<AiConversation>()
                            .eq(AiConversation::getConversationNo, conversationNo)
                            .eq(AiConversation::getUserId, userId));
            if (exist != null) {
                return exist;
            }
            throw new BusinessException(404, "会话不存在");
        }
        AiConversation conv = new AiConversation();
        conv.setConversationNo(UUID.randomUUID().toString().replace("-", ""));
        conv.setUserId(userId);
        conv.setMessageCount(0);
        conv.setCreateTime(LocalDateTime.now());
        conv.setUpdateTime(LocalDateTime.now());
        conversationMapper.insert(conv);
        return conv;
    }

    /** 保存一条 user/assistant 消息 */
    public void saveMessage(Long conversationId, String role, String content) {
        AiMessage msg = new AiMessage();
        msg.setConversationId(conversationId);
        msg.setRole(role);
        msg.setContent(content);
        msg.setCreateTime(LocalDateTime.now());
        msg.setUpdateTime(LocalDateTime.now());
        messageMapper.insert(msg);
    }

    /** 保存一条 tool 消息(记录工具名/入参/原始返回, 便于回放决策链路) */
    public void saveToolMessage(Long conversationId, String toolName, String toolArgs, String toolResult) {
        AiMessage msg = new AiMessage();
        msg.setConversationId(conversationId);
        msg.setRole("tool");
        msg.setToolName(toolName);
        msg.setToolArgs(toolArgs);
        msg.setToolResult(toolResult);
        msg.setCreateTime(LocalDateTime.now());
        msg.setUpdateTime(LocalDateTime.now());
        messageMapper.insert(msg);
    }

    /**
     * 最近 N 条消息(按 id 倒序取再反转成时间正序), 用于拼对话上下文。
     */
    public List<AiMessage> recentMessages(Long conversationId, int limit) {
        List<AiMessage> list = messageMapper.selectList(
                new LambdaQueryWrapper<AiMessage>()
                        .eq(AiMessage::getConversationId, conversationId)
                        .orderByDesc(AiMessage::getId)
                        .last("LIMIT " + Math.min(Math.max(limit, 1), MAX_HISTORY_MESSAGES)));
        Collections.reverse(list);
        return list;
    }

    /** 更新会话冗余字段(标题/消息数/最后消息时间) */
    public void refreshConversation(Long conversationId, String title, int messageCount) {
        AiConversation update = new AiConversation();
        update.setId(conversationId);
        if (title != null) {
            update.setTitle(title);
        }
        update.setMessageCount(messageCount);
        update.setLastMessageTime(LocalDateTime.now());
        update.setUpdateTime(LocalDateTime.now());
        conversationMapper.updateById(update);
    }

    /** 会话总数(更新 message_count 冗余字段用) */
    public long countMessages(Long conversationId) {
        Long count = messageMapper.selectCount(
                new LambdaQueryWrapper<AiMessage>().eq(AiMessage::getConversationId, conversationId));
        return count == null ? 0 : count;
    }

    /** 某用户的会话列表(按最后消息时间倒序) */
    public List<AiConversation> listConversations(Long userId) {
        return conversationMapper.selectList(new LambdaQueryWrapper<AiConversation>()
                .eq(AiConversation::getUserId, userId)
                .orderByDesc(AiConversation::getLastMessageTime));
    }

    /** 会话内消息升序(先校验会话归属) */
    public List<AiMessage> listMessages(Long userId, String conversationNo) {
        AiConversation conv = conversationMapper.selectOne(
                new LambdaQueryWrapper<AiConversation>()
                        .eq(AiConversation::getConversationNo, conversationNo)
                        .eq(AiConversation::getUserId, userId));
        if (conv == null) {
            throw new BusinessException(404, "会话不存在");
        }
        return messageMapper.selectList(new LambdaQueryWrapper<AiMessage>()
                .eq(AiMessage::getConversationId, conv.getId())
                .orderByAsc(AiMessage::getId));
    }

    /**
     * 删除会话及其全部消息(先校验归属)。
     * 两张实体表均有 @TableLogic, 实际执行逻辑删除(is_deleted=1)。
     */
    public void deleteConversation(Long userId, String conversationNo) {
        AiConversation conv = conversationMapper.selectOne(
                new LambdaQueryWrapper<AiConversation>()
                        .eq(AiConversation::getConversationNo, conversationNo)
                        .eq(AiConversation::getUserId, userId));
        if (conv == null) {
            throw new BusinessException(404, "会话不存在");
        }
        conversationMapper.deleteById(conv.getId());
        messageMapper.delete(new LambdaQueryWrapper<AiMessage>()
                .eq(AiMessage::getConversationId, conv.getId()));
    }
}
