package com.example.ai.controller;

import com.example.ai.dto.AiChatRequest;
import com.example.ai.dto.AiChatResponse;
import com.example.ai.dto.AiMessageVO;
import com.example.ai.dto.ConversationVO;
import com.example.ai.entity.AiConversation;
import com.example.ai.entity.AiMessage;
import com.example.ai.agent.AgentService;
import com.example.ai.service.ConversationService;
import com.example.common.result.Result;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI 智能导购助手接口(经网关 /ai/** 路由, 需登录)。
 * userId 由网关从 JWT 解析后经 X-User-Id 请求头透传。
 */
@RestController
@RequestMapping("/ai")
public class AiChatController {

    @Resource
    private AgentService agentService;
    @Resource
    private ConversationService conversationService;

    /**
     * 对话(核心入口): body { conversationNo?, message }
     */
    @PostMapping("/chat")
    public Result<AiChatResponse> chat(@RequestHeader("X-User-Id") Long userId,
                                       @RequestBody AiChatRequest req) {
        return Result.success(agentService.chat(userId, req));
    }

    /**
     * 会话列表(左侧聊天抽屉, 按最后消息时间倒序)
     */
    @GetMapping("/conversations")
    public Result<List<ConversationVO>> conversations(@RequestHeader("X-User-Id") Long userId) {
        List<ConversationVO> list = conversationService.listConversations(userId).stream()
                .map(ConversationVO::new)
                .toList();
        return Result.success(list);
    }

    /**
     * 会话历史消息(升序)
     */
    @GetMapping("/conversations/{conversationNo}/messages")
    public Result<List<AiMessageVO>> messages(@RequestHeader("X-User-Id") Long userId,
                                              @PathVariable String conversationNo) {
        List<AiMessageVO> list = conversationService.listMessages(userId, conversationNo).stream()
                .map(AiMessageVO::new)
                .toList();
        return Result.success(list);
    }
}
