package com.alibaba.assistant.agent.extension.experience.hook;

import com.alibaba.assistant.agent.common.hook.AgentPhase;
import com.alibaba.assistant.agent.common.hook.HookPhases;
import com.alibaba.assistant.agent.extension.experience.config.ExperienceExtensionProperties;
import com.alibaba.assistant.agent.extension.experience.model.Experience;
import com.alibaba.assistant.agent.extension.experience.model.ExperienceQuery;
import com.alibaba.assistant.agent.extension.experience.model.ExperienceQueryContext;
import com.alibaba.assistant.agent.extension.experience.model.ExperienceType;
import com.alibaba.assistant.agent.extension.experience.spi.ExperienceProvider;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.JumpTo;
import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import com.alibaba.fastjson.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 代码经验模型Hook
 * 参考记忆模块实现，在BEFORE_MODEL阶段检测代码生成场景并注入相关代码经验
 *
 * 核心设计：
 * 1. 检测是否是代码生成相关的请求（通过messages内容判断）
 * 2. 查询匹配的代码经验（语言、标签等）
 * 3. 直接修改messages列表，注入代码经验
 *
 * @author Assistant Agent Team
 */
@HookPhases(AgentPhase.CODEACT)
@HookPositions(HookPosition.BEFORE_MODEL)
public class CodeExperienceModelHook extends ModelHook {

    private static final Logger log = LoggerFactory.getLogger(CodeExperienceModelHook.class);

    private final ExperienceProvider experienceProvider;
    private final ExperienceExtensionProperties properties;

    public CodeExperienceModelHook(ExperienceProvider experienceProvider,
                                  ExperienceExtensionProperties properties) {
        this.experienceProvider = experienceProvider;
        this.properties = properties;
    }

    @Override
    public String getName() {
        return "CodeExperienceModelHook";
    }

    @Override
    public List<JumpTo> canJumpTo() {
        return List.of();
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<Map<String, Object>> beforeModel(OverAllState state, RunnableConfig config) {
        log.info("CodeExperienceModelHook#beforeModel - reason=开始检测并注入代码经验");

        try {
            // 检查模块是否启用
            if (!properties.isEnabled() || !properties.isCodeExperienceEnabled()) {
                log.info("CodeExperienceModelHook#beforeModel - reason=代码经验模块未启用，跳过");
                return CompletableFuture.completedFuture(Map.of());
            }

            // 检查是否是代码相关请求
            Optional<Object> messagesOpt = state.value("codeact_node_messages");
            if (messagesOpt.isEmpty()) {
                messagesOpt = state.value("messages");
                if (messagesOpt.isEmpty()) {
                    log.warn("CodeExperienceModelHook#beforeModel - reason=state中没有messages，跳过");
                    return CompletableFuture.completedFuture(Map.of());
                }
            }

            List<Message> messages = (List<Message>) messagesOpt.get();
            if (!isCodeRelatedRequest(messages)) {
                log.debug("CodeExperienceModelHook#beforeModel - reason=非代码相关请求，跳过");
                return CompletableFuture.completedFuture(Map.of());
            }

            // 获取用户输入，用于向量搜索
            String userInput = state.value("input", String.class).orElse(null);

            // 构造查询上下文
            ExperienceQueryContext context = buildQueryContext(state, config, messages, userInput);

            // 查询代码经验
            ExperienceQuery query = buildCodeQuery(context, userInput);
            List<Experience> experiences = experienceProvider.query(query, context);

            if (CollectionUtils.isEmpty(experiences)) {
                log.info("CodeExperienceModelHook#beforeModel - reason=未找到匹配的代码经验");
                return CompletableFuture.completedFuture(Map.of());
            }

            log.info("CodeExperienceModelHook#beforeModel - reason=找到代码经验: {}", JSON.toJSONString(experiences));

            // 🔥 核心：直接修改messages列表
            return injectCodeExperienceToMessages(state, experiences);

        } catch (Exception e) {
            log.error("CodeExperienceModelHook#beforeModel - reason=注入代码经验失败", e);
            return CompletableFuture.completedFuture(Map.of());
        }
    }

    /**
     * 检测是否是代码相关的请求
     */
    private boolean isCodeRelatedRequest(List<Message> messages) {
        String allContent = messages.stream()
                .map(Message::getText)
                .reduce("", (a, b) -> a + " " + b)
                .toLowerCase();

        return allContent.contains("代码") ||
               allContent.contains("code") ||
               allContent.contains("python") ||
               allContent.contains("java") ||
               allContent.contains("函数") ||
               allContent.contains("demo") ||
               allContent.contains("生成") ||
               allContent.contains("write");
    }

    /**
     * 构建代码经验查询条件
     */
    private ExperienceQuery buildCodeQuery(ExperienceQueryContext context, String userInput) {
        ExperienceQuery query = new ExperienceQuery(ExperienceType.CODE);
        query.setLimit(properties.getMaxItemsPerQuery());

        // 关键修复：设置查询文本，用于向量搜索
        if (StringUtils.hasText(userInput)) {
            query.setText(userInput);
        }

        // 设置语言
        if (StringUtils.hasText(context.getLanguage())) {
            query.setLanguage(context.getLanguage());
        }

        // 设置标签（例如 demo 关键词）
        if (context.getSceneTags() != null && !context.getSceneTags().isEmpty()) {
            query.setTags(Set.of(context.getSceneTags()));
        }

        return query;
    }

    /**
     * 🔥 核心方法：注入代码经验到messages
     * 使用 AssistantMessage + ToolResponseMessage 配对方式
     */
    @SuppressWarnings("unchecked")
    private CompletableFuture<Map<String, Object>> injectCodeExperienceToMessages(OverAllState state, List<Experience> experiences) {
        log.info("CodeExperienceModelHook#injectCodeExperienceToMessages - reason=开始处理messages");

        try {
            Optional<Object> messagesOpt = state.value("messages");
            if (messagesOpt.isEmpty()) {
                log.warn("CodeExperienceModelHook#injectCodeExperienceToMessages - reason=state中没有messages，跳过");
                return CompletableFuture.completedFuture(Map.of());
            }

            List<Message> messages = (List<Message>) messagesOpt.get();
            log.debug("CodeExperienceModelHook#injectCodeExperienceToMessages - reason=当前messages数量={}", messages.size());

            // 🔥 检查是否已经注入过代码经验（避免重复注入）
            for (Message msg : messages) {
                if (msg instanceof ToolResponseMessage toolMsg) {
                    for (ToolResponseMessage.ToolResponse response : toolMsg.getResponses()) {
                        if ("code_experience_injection".equals(response.name())) {
                            log.info("CodeExperienceModelHook#injectCodeExperienceToMessages - reason=检测到已注入代码经验，跳过重复注入");
                            return CompletableFuture.completedFuture(Map.of());
                        }
                    }
                }
            }

            // 构建代码经验内容
            String codeExperienceContent = buildCodeExperienceContent(experiences);
            log.debug("CodeExperienceModelHook#injectCodeExperienceToMessages - reason=经验内容构建完成，长度={}", codeExperienceContent.length());

            // 🔥 构造 AssistantMessage + ToolResponseMessage 配对
            String toolCallId = "code_exp_" + UUID.randomUUID().toString().substring(0, 8);

            // 1. AssistantMessage with toolCall
            AssistantMessage assistantMessage = AssistantMessage.builder()
                .toolCalls(List.of(
                    new AssistantMessage.ToolCall(
                        toolCallId,
                        "function",
                        "code_experience_injection",
                        "{}"  // 空参数
                    )
                ))
                .build();

            // 2. ToolResponseMessage with response
            ToolResponseMessage.ToolResponse toolResponse = new ToolResponseMessage.ToolResponse(
                toolCallId,
                "code_experience_injection",
                codeExperienceContent
            );

            ToolResponseMessage toolResponseMessage = ToolResponseMessage.builder()
                .responses(List.of(toolResponse))
                .build();

            log.info("CodeExperienceModelHook#injectCodeExperienceToMessages - reason=准备注入代码经验（AssistantMessage + ToolResponseMessage）");

            // 🔥 返回配对的两条消息
            Map<String, Object> updates = Map.of("messages", List.of(assistantMessage, toolResponseMessage));
            log.info("CodeExperienceModelHook#injectCodeExperienceToMessages - reason=准备返回updates，keys={}", updates.keySet());

            return CompletableFuture.completedFuture(updates);

        } catch (Exception e) {
            log.error("CodeExperienceModelHook#injectCodeExperienceToMessages - reason=修改messages失败", e);
            return CompletableFuture.completedFuture(Map.of());
        }
    }

    /**
     * 构建代码经验内容
     */
    private String buildCodeExperienceContent(List<Experience> experiences) {
        StringBuilder content = new StringBuilder();

        content.append("=== 代码生成指导经验 ===\n\n");

        for (Experience experience : experiences) {
            content.append("💡 ").append(experience.getTitle()).append("\n");

            // 使用 getEffectiveContent()，自动从 artifact.code 生成内容（如果 content 为空）
            String effectiveContent = experience.getEffectiveContent();
            if (StringUtils.hasText(effectiveContent)) {
                String trimmedContent = effectiveContent;
                if (trimmedContent.length() > properties.getMaxContentLength()) {
                    trimmedContent = trimmedContent.substring(0, properties.getMaxContentLength()) + "...";
                }
                content.append(trimmedContent).append("\n\n");
            }

            // 如果有标签，也显示出来
            if (experience.getTags() != null && !experience.getTags().isEmpty()) {
                content.append("适用场景: ").append(String.join(", ", experience.getTags())).append("\n\n");
            }
        }

        content.append("请参考以上经验完成代码生成任务。");

        return content.toString();
    }

    /**
     * 构建查询上下文
     */
    private ExperienceQueryContext buildQueryContext(OverAllState state, RunnableConfig config, List<Message> messages, String userQuery) {
        ExperienceQueryContext context = new ExperienceQueryContext();

        // 关键修复：设置userQuery，用于向量搜索
        if (StringUtils.hasText(userQuery)) {
            context.setUserQuery(userQuery);
        }

        // 从state提取
        if (state != null) {
            state.value("user_id", String.class).ifPresent(context::setUserId);
            state.value("project_id", String.class).ifPresent(context::setProjectId);
            state.value("language", String.class).ifPresent(context::setLanguage);
        }

        // 从config提取
        if (config != null) {
            config.metadata("language").ifPresent(lang -> context.setLanguage(lang.toString()));
        }

        // 检测 demo 关键词
        String allContent = messages.stream()
                .map(Message::getText)
                .reduce("", (a, b) -> a + " " + b)
                .toLowerCase();

        if (allContent.contains("demo")) {
            context.setSceneTags("demo");
            context.setLanguage("python");
            log.info("CodeExperienceModelHook#buildQueryContext - reason=Detected demo keyword");
        }

        context.setTaskType("code_generation");

        return context;
    }
}
