package com.example.ai.agent;

import com.example.ai.config.AiProperties;
import com.example.ai.config.ResourceTextLoader;
import com.example.ai.dto.AiChatRequest;
import com.example.ai.dto.AiChatResponse;
import com.example.ai.entity.AiConversation;
import com.example.ai.entity.AiMessage;
import com.example.ai.exception.BusinessException;
import com.example.ai.llm.LlmClient;
import com.example.ai.llm.LlmMessage;
import com.example.ai.llm.LlmToolCall;
import com.example.ai.llm.LlmToolDef;
import com.example.ai.rag.RagService;
import com.example.ai.service.ConversationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 主服务: 「组装上下文 → LLM ↔ 工具循环(最多 max-tool-rounds 轮) → 落库回复」。
 *
 * 单轮对话流程:
 *  1. 取/建会话, 保存用户消息;
 *  2. system = 提示词 + RAG 检索到的知识库片段; 历史取最近 N 轮 user/assistant 消息(tool 消息不进历史);
 *  3. 循环调模型: 返回 tool_calls 则逐个执行工具, 结果以 role=tool 消息追加后继续下一轮;
 *     返回纯文本则为最终回复, 跳出循环;
 *  4. 轮次耗尽仍未得到文本时, 不带 tools 再调一次, 强制模型给出口头答复;
 *  5. 保存 assistant 回复并刷新会话冗余字段。
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    /** 工具原始返回落库截断长度(t_ai_message.tool_result 为 MEDIUMTEXT, 仅防极端超长) */
    private static final int TOOL_RESULT_MAX_LEN = 4000;

    @Resource
    private AiProperties props;
    @Resource
    private ResourceTextLoader textLoader;
    @Resource
    private LlmClient llmClient;
    @Resource
    private RagService ragService;
    @Resource
    private ToolExecutor toolExecutor;
    @Resource
    private ConversationService conversationService;
    @Resource
    private ObjectMapper objectMapper;

    /** 工具定义(启动时从 tools.schema.json 加载一次) */
    private List<LlmToolDef> toolDefs;

    @PostConstruct
    public void init() {
        try {
            JsonNode root = objectMapper.readTree(textLoader.load(props.getToolsPath()));
            toolDefs = objectMapper.convertValue(root.path("tools"),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, LlmToolDef.class));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("工具定义解析失败: " + props.getToolsPath(), e);
        }
    }

    /**
     * 对话入口
     * @param userId 当前登录用户(网关透传的 X-User-Id)
     */
    public AiChatResponse chat(Long userId, AiChatRequest req) {
        if (req == null || req.getMessage() == null || req.getMessage().isBlank()) {
            throw new BusinessException(400, "消息内容不能为空");
        }
        AiConversation conv = conversationService.createIfAbsent(userId, req.getConversationNo());
        conversationService.saveMessage(conv.getId(), "user", req.getMessage());

        String reply;
        try {
            reply = runLoop(userId, conv.getId(), req.getMessage());
        } catch (Exception e) {
            log.error("AI 对话失败: userId={}", userId, e);
            // 兜底话术(同样落库, 便于排查)
            reply = props.getFallback().getErrorReply();
        }
        conversationService.saveMessage(conv.getId(), "assistant", reply);

        // 刷新会话冗余字段: 首条用户消息前 20 字作标题, 消息数实时统计
        long count = conversationService.countMessages(conv.getId());
        String title = conv.getTitle() == null
                ? req.getMessage().substring(0, Math.min(20, req.getMessage().length()))
                : null;
        conversationService.refreshConversation(conv.getId(), title, (int) count);
        return new AiChatResponse(conv.getConversationNo(), reply);
    }

    /** 核心: LLM ↔ 工具循环 */
    private String runLoop(Long userId, Long conversationId, String userMessage) {
        // 1) system: 提示词 + RAG 检索片段(规则类问题给模型提供依据)
        String system = textLoader.load(props.getPromptPath());
        String ragContext = ragService.searchContext(userMessage);
        if (!ragContext.isEmpty()) {
            system = system + "\n\n" + ragContext;
        }
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.of("system", system));

        // 2) 历史: 最近 N 轮 user/assistant 消息(tool 中间消息不进历史, 上下文更省)
        int historyRounds = props.getLlm().getHistoryRounds() == null ? 6 : props.getLlm().getHistoryRounds();
        List<AiMessage> history = conversationService.recentMessages(conversationId, historyRounds * 2);
        for (AiMessage m : history) {
            if ("user".equals(m.getRole()) || "assistant".equals(m.getRole())) {
                messages.add(LlmMessage.of(m.getRole(), m.getContent()));
            }
        }
        // 3) 本轮输入
        messages.add(LlmMessage.of("user", userMessage));

        // 4) 工具循环(最多 max-tool-rounds 轮)
        int maxRounds = props.getLlm().getMaxToolRounds() == null ? 5 : props.getLlm().getMaxToolRounds();
        LlmMessage current = null;
        for (int round = 0; round < maxRounds; round++) {
            current = llmClient.chat(messages, toolDefs);
            if (current.getToolCalls() == null || current.getToolCalls().isEmpty()) {
                return current.getContent(); // 纯文本: 最终回复
            }
            messages.add(current); // assistant 消息(带 tool_calls, 协议要求回传)
            for (LlmToolCall call : current.getToolCalls()) {
                String name = call.getFunction().getName();
                String argsJson = call.getFunction().getArguments();
                String result = toolExecutor.execute(userId, name, argsJson);
                // 工具调用全链路落库(答辩时可回放决策过程)
                conversationService.saveToolMessage(conversationId, name, argsJson,
                        truncate(result, TOOL_RESULT_MAX_LEN));
                LlmMessage toolMsg = new LlmMessage();
                toolMsg.setRole("tool");
                toolMsg.setContent(result);
                toolMsg.setToolCallId(call.getId());
                toolMsg.setName(name);
                messages.add(toolMsg);
            }
        }
        // 5) 轮次耗尽仍无文本: 不带 tools 强制要一次口头答复
        return llmClient.chat(messages, null).getContent();
    }

    private String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }
}
