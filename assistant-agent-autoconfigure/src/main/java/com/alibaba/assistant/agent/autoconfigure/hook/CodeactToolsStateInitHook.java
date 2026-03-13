/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.assistant.agent.autoconfigure.hook;

import com.alibaba.assistant.agent.common.constant.CodeactStateKeys;
import com.alibaba.assistant.agent.common.constant.HookPriorityConstants;
import com.alibaba.assistant.agent.common.tools.CodeactTool;
import com.alibaba.assistant.agent.core.tool.CodeactToolRegistry;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.Prioritized;
import com.alibaba.cloud.ai.graph.agent.hook.AgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * CodeactTools 状态初始化 Hook
 *
 * <p>在 BEFORE_AGENT 阶段最先执行，将 {@link CodeactToolRegistry} 中注册的所有
 * 工具名称注入到 {@link OverAllState} 的 {@code codeact_tool_names} 键中。
 *
 * <p><b>重要</b>：为避免序列化问题，不再将完整的 {@link CodeactTool} 对象写入 State。
 * 完整的工具对象应通过 {@link CodeactToolRegistry} 获取，而不是从 State 中读取。
 * 
 * <p>写入 State 的数据：
 * <ul>
 *   <li>{@code codeact_tool_names} - 所有工具名称列表 (List&lt;String&gt;)，可序列化</li>
 * </ul>
 *
 * <p>优先级为 {@value #ORDER}，确保在所有其他 BEFORE_AGENT Hook 之前执行。
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@HookPositions(HookPosition.BEFORE_AGENT)
public class CodeactToolsStateInitHook extends AgentHook implements Prioritized {

    private static final Logger log = LoggerFactory.getLogger(CodeactToolsStateInitHook.class);

    /**
     * 优先级设置为 {@link HookPriorityConstants#CODEACT_TOOLS_STATE_INIT_HOOK}，
     * 确保在所有其他 BEFORE_AGENT Hook 之前执行。
     * <p>
     * 执行顺序：
     * <ol>
     *   <li>CodeactToolsStateInitHook (5) - 注入 codeact_tool_names 到 state</li>
     *   <li>ReactExperienceHook (20) - React 经验注入</li>
     *   <li>TaskTreeInitHook (30) - 任务树初始化</li>
     *   <li>FastIntentHook (50) - 快速意图判断</li>
     *   <li>EvaluationHook (100) - 评估（通过 Registry 获取工具）</li>
     *   <li>PromptContributorHook (200) - Prompt 注入</li>
     * </ol>
     */
    private static final int ORDER = HookPriorityConstants.CODEACT_TOOLS_STATE_INIT_HOOK;

    private final CodeactToolRegistry codeactToolRegistry;

    public CodeactToolsStateInitHook(CodeactToolRegistry codeactToolRegistry) {
        this.codeactToolRegistry = codeactToolRegistry;
    }

    @Override
    public String getName() {
        return "CodeactToolsStateInitHook";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    /**
     * 获取 CodeactToolRegistry 实例
     * <p>
     * 供其他组件使用，以便从 Registry 中获取完整的工具对象。
     * 
     * @return CodeactToolRegistry 实例
     */
    public CodeactToolRegistry getCodeactToolRegistry() {
        return codeactToolRegistry;
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeAgent(OverAllState state, RunnableConfig config) {
        List<CodeactTool> allTools = codeactToolRegistry.getAllTools();
        
        // 只写入工具名称列表，避免序列化 CodeactTool 对象（其中包含不可序列化的 DefaultToolDefinition）
        List<String> toolNames = allTools.stream()
                .map(CodeactTool::getName)
                .collect(Collectors.toList());

        Map<String, Object> updates = new HashMap<>();
        updates.put(CodeactStateKeys.CODEACT_TOOL_NAMES, toolNames);

        log.info("CodeactToolsStateInitHook#beforeAgent - reason=注入codeact_tool_names到state, toolCount={}",
                toolNames.size());

        return CompletableFuture.completedFuture(updates);
    }
}

