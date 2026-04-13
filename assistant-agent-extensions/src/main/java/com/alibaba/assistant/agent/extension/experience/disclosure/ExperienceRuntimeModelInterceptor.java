package com.alibaba.assistant.agent.extension.experience.disclosure;

import com.alibaba.assistant.agent.common.constant.CodeactStateKeys;
import com.alibaba.assistant.agent.extension.experience.disclosure.ExperienceDisclosurePayloads.ReadExpRequest;
import com.alibaba.assistant.agent.extension.experience.disclosure.ExperienceDisclosurePayloads.ReadExpResponse;
import com.alibaba.assistant.agent.extension.experience.disclosure.ExperienceDisclosurePayloads.SearchExpRequest;
import com.alibaba.assistant.agent.extension.experience.disclosure.ExperienceDisclosurePayloads.SearchExpResponse;
import com.alibaba.assistant.agent.extension.experience.model.ExperienceQueryContext;
import com.alibaba.fastjson.JSON;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

import static com.alibaba.cloud.ai.graph.agent.tools.ToolContextConstants.AGENT_CONFIG_CONTEXT_KEY;
import static com.alibaba.cloud.ai.graph.agent.tools.ToolContextConstants.AGENT_STATE_CONTEXT_KEY;

/**
 * Exposes {@code search_exp}/{@code read_exp} and gates direct TOOL calls per request.
 *
 * <p>This interceptor is the runtime bridge between the experience store and the React
 * loop: it registers the disclosure tools and trims the visible direct-call tool set so
 * only experiences disclosed in the current state can be invoked directly by the model.
 */
public class ExperienceRuntimeModelInterceptor extends ModelInterceptor {

    public static final String SEARCH_EXP_TOOL_NAME = "search_exp";
    public static final String READ_EXP_TOOL_NAME = "read_exp";

    private final ExperienceDisclosureService experienceDisclosureService;
    private final ExperienceDisclosureContextResolver contextResolver;
    private final ExperienceToolInvocationClassifier toolInvocationClassifier;
    private final List<ToolCallback> tools;

    public ExperienceRuntimeModelInterceptor(ExperienceDisclosureService experienceDisclosureService,
                                             ExperienceDisclosureContextResolver contextResolver,
                                             ExperienceToolInvocationClassifier toolInvocationClassifier) {
        this.experienceDisclosureService = experienceDisclosureService;
        this.contextResolver = contextResolver;
        this.toolInvocationClassifier = toolInvocationClassifier;
        this.tools = List.of(buildSearchTool(), buildReadTool());
    }

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        if (request.getTools() == null || request.getTools().isEmpty()) {
            return handler.call(request);
        }
        Set<String> allowedDirectToolNames = extractAllowedDirectToolNames(request.getContext());
        List<String> filteredTools = new ArrayList<>();
        for (String toolName : request.getTools()) {
            if (!isReactDirectFunctionTool(toolName) || allowedDirectToolNames.contains(toolName)) {
                filteredTools.add(toolName);
            }
        }
        ModelRequest filteredRequest = ModelRequest.builder(request)
                .tools(filteredTools)
                .build();
        return handler.call(filteredRequest);
    }

    @Override
    public String getName() {
        return "ExperienceRuntimeModelInterceptor";
    }

    @Override
    public List<ToolCallback> getTools() {
        return tools;
    }

    private ToolCallback buildSearchTool() {
        BiFunction<SearchExpRequest, ToolContext, String> function = (request, toolContext) -> {
            OverAllState state = extractState(toolContext);
            RunnableConfig config = extractConfig(toolContext);
            String query = request != null ? request.getQuery() : null;
            if (!StringUtils.hasText(query)) {
                query = contextResolver.resolveQuery(state, config);
            }
            ExperienceQueryContext queryContext = contextResolver.buildQueryContext(state, config, query);
            SearchExpResponse response = experienceDisclosureService.search(
                    query,
                    request != null ? request.getCommonLimit() : null,
                    request != null ? request.getReactLimit() : null,
                    request != null ? request.getToolLimit() : null,
                    queryContext
            );
            return JSON.toJSONString(response);
        };
        return FunctionToolCallback.<SearchExpRequest, String>builder(SEARCH_EXP_TOOL_NAME, function)
                .description("按关键词搜索经验候选，返回 COMMON（概念术语）、REACT（工作流策略）、TOOL（工具能力）三类分组结果。")
                .inputType(SearchExpRequest.class)
                .build();
    }

    private ToolCallback buildReadTool() {
        BiFunction<ReadExpRequest, ToolContext, String> function = (request, toolContext) -> {
            ReadExpResponse response = experienceDisclosureService.read(request != null ? request.getId() : null);
            return JSON.toJSONString(response);
        };
        return FunctionToolCallback.<ReadExpRequest, String>builder(READ_EXP_TOOL_NAME, function)
                .description("根据经验 id 读取完整详情，包括内容正文、关联工件、调用路径等。用于获取 PROGRESSIVE 候选的完整内容。")
                .inputType(ReadExpRequest.class)
                .build();
    }

    private Set<String> extractAllowedDirectToolNames(Map<String, Object> context) {
        if (context == null) {
            return Set.of();
        }
        Object value = context.get(CodeactStateKeys.EXPERIENCE_ALLOWED_REACT_TOOL_NAMES);
        if (!(value instanceof List<?> list) || CollectionUtils.isEmpty(list)) {
            return Set.of();
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (Object item : list) {
            if (item != null) {
                names.add(String.valueOf(item));
            }
        }
        return names;
    }

    private boolean isReactDirectFunctionTool(String toolName) {
        if (!StringUtils.hasText(toolName)) {
            return false;
        }
        if (toolName.contains(".")) {
            return true;
        }
        return toolInvocationClassifier != null && toolInvocationClassifier.isReactDirectTool(toolName);
    }

    private OverAllState extractState(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object state = toolContext.getContext().get(AGENT_STATE_CONTEXT_KEY);
        return state instanceof OverAllState overAllState ? overAllState : null;
    }

    private RunnableConfig extractConfig(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object config = toolContext.getContext().get(AGENT_CONFIG_CONTEXT_KEY);
        return config instanceof RunnableConfig runnableConfig ? runnableConfig : null;
    }
}
