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
package com.alibaba.assistant.agent.autoconfigure.tools;

import com.alibaba.assistant.agent.core.context.CodeContext;
import com.alibaba.assistant.agent.core.context.SessionCodeManager;
import com.alibaba.assistant.agent.core.executor.RuntimeEnvironmentManager;
import com.alibaba.assistant.agent.core.model.GeneratedCode;
import com.alibaba.assistant.agent.extension.experience.fastintent.CodeFastIntentSupport;
import com.alibaba.assistant.agent.extension.experience.model.Experience;
import com.alibaba.assistant.agent.extension.experience.model.FastIntentConfig;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextConstants;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * WriteConditionCodeTool - 条件判断代码注册工具
 *
 * <p>职责：
 * <ul>
 * <li>接收 LLM 在 React 阶段直接生成的条件判断函数代码</li>
 * <li>验证代码格式（函数名、参数、返回值类型）</li>
 * <li>将代码注入到 CodeContext，供触发器使用</li>
 * </ul>
 *
 * <p>说明：
 * <ul>
 * <li>新增 code 参数，直接接收完整的 Python 条件函数代码</li>
 * <li>条件函数必须返回 boolean 值（True/False）</li>
 * </ul>
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public class WriteConditionCodeTool implements BiFunction<WriteConditionCodeTool.Request, ToolContext, String> {

	private static final Logger logger = LoggerFactory.getLogger(WriteConditionCodeTool.class);

	private final CodeContext codeContext;
	private final RuntimeEnvironmentManager environmentManager;

	private final CodeFastIntentSupport codeFastIntentSupport;

	public WriteConditionCodeTool(CodeContext codeContext,
								  RuntimeEnvironmentManager environmentManager,
								  CodeFastIntentSupport codeFastIntentSupport) {
		this.codeContext = codeContext;
		this.environmentManager = environmentManager;
		this.codeFastIntentSupport = codeFastIntentSupport;
	}

	public WriteConditionCodeTool(CodeContext codeContext, RuntimeEnvironmentManager environmentManager) {
		this(codeContext, environmentManager, null);
	}

	@Override
	public String apply(Request request, ToolContext toolContext) {
		logger.info("WriteConditionCodeTool#apply 注册条件判断代码: functionName={}", request.functionName);

		try {
			// 0. FastPath Intent (CODE): hit => skip validation
			String fastIntentResult = tryFastIntent(request, toolContext);
			if (fastIntentResult != null) {
				return fastIntentResult;
			}

			// 1. 验证必填参数
			if (request.functionName == null || request.functionName.trim().isEmpty()) {
				return "Error: functionName is required";
			}
			if (request.code == null || request.code.trim().isEmpty()) {
				return "Error: code is required. Please provide the complete Python condition function code.";
			}

			// 2. 清理代码
			String cleanedCode = cleanUpCode(request.code);

			// 3. 验证代码格式
			String validationError = validateCode(request.functionName, request.parameters, cleanedCode);
			if (validationError != null) {
				logger.warn("WriteConditionCodeTool#apply 代码验证失败: {}", validationError);
				return "Error: " + validationError;
			}

			// 4. 注册代码到 CodeContext
			registerCode(request, cleanedCode, toolContext);

			logger.info("WriteConditionCodeTool#apply 条件判断代码注册成功: functionName={}", request.functionName);
			return String.format("条件函数 %s 已成功注册，可用于触发器订阅。\n```python\n%s\n```",
					request.functionName, cleanedCode);

		} catch (Exception e) {
			logger.error("WriteConditionCodeTool#apply 条件判断代码注册失败", e);
			return "Error: " + e.getMessage();
		}
	}

	@SuppressWarnings("unchecked")
	private String tryFastIntent(Request request, ToolContext toolContext) {
		try {
			if (codeFastIntentSupport == null || toolContext == null) {
				return null;
			}

			String language = (codeContext != null && codeContext.getLanguage() != null) ? codeContext.getLanguage().name() : null;
			Map<String, Object> toolReq = CodeFastIntentSupport.toolReqOf(request.description, request.functionName, request.parameters);
			Optional<CodeFastIntentSupport.Hit> hitOpt = codeFastIntentSupport.tryHit(toolContext, toolReq, language);
			if (hitOpt.isEmpty()) {
				return null;
			}

			Experience best = hitOpt.get().experience();
			String code = hitOpt.get().code();
			if (code == null) {
				return null;
			}

			try {
				registerCode(request, code, toolContext);
			} catch (Exception e) {
				String err = e.getMessage();
				logger.warn("WriteConditionCodeTool#tryFastIntent - reason=fast-intent register failed, expId={}, error={}",
						best != null ? best.getId() : "unknown", err);

				FastIntentConfig.FastIntentFallback fb = CodeFastIntentSupport.getOnRegisterFallback(best);
				if (fb == FastIntentConfig.FastIntentFallback.FAIL_FAST) {
					return "Error: FastIntent(Code) register failed: " + err;
				}
				return null;
			}

			logger.info("WriteConditionCodeTool#tryFastIntent - reason=fast-intent HIT (skip codegen), expId={}",
					best != null ? best.getId() : "unknown");
			return "FastIntent(Code) hit: " + (best != null ? best.getTitle() : "matched") + "\n```python\n" + code + "\n```";

		} catch (Exception e) {
			logger.warn("WriteConditionCodeTool#tryFastIntent - reason=fast-intent failed, fallback to normal flow, error={}", e.getMessage());
			return null;
		}
	}

	/**
	 * 清理代码（移除 markdown 代码块标记）
	 */
	private String cleanUpCode(String code) {
		if (code == null) {
			return null;
		}
		String cleaned = code.trim();
		if (cleaned.startsWith("```python")) {
			cleaned = cleaned.substring(9);
		} else if (cleaned.startsWith("```py")) {
			cleaned = cleaned.substring(5);
		} else if (cleaned.startsWith("```")) {
			cleaned = cleaned.substring(3);
		}
		if (cleaned.endsWith("```")) {
			cleaned = cleaned.substring(0, cleaned.length() - 3);
		}
		return cleaned.trim();
	}

	/**
	 * 验证代码格式
	 */
	private String validateCode(String expectedFunctionName, List<String> expectedParameters, String code) {
		String actualFunctionName = environmentManager.extractFunctionName(code);
		if (actualFunctionName == null) {
			return "Code does not contain a valid function definition. Expected: def " + expectedFunctionName + "(...) -> bool";
		}
		if (!actualFunctionName.equals(expectedFunctionName)) {
			return String.format("Function name mismatch. Expected: %s, Actual: %s",
					expectedFunctionName, actualFunctionName);
		}
		return null;
	}

	/**
	 * 注册代码到 Session状态 和 Store
	 *
	 * <p>代码会被保存到OverAllState的session级别存储中，而不是全局共享的CodeContext，
	 * 这样不同session的代码不会相互干扰。
	 */
	private void registerCode(Request request, String conditionCode, ToolContext toolContext) {
		// 创建 GeneratedCode 对象
		GeneratedCode code = new GeneratedCode(
				request.functionName,
				codeContext.getLanguage(),
				conditionCode,
				request.description != null ? request.description : ""
		);
		code.setParameters(request.parameters != null ? new ArrayList<>(request.parameters) : new ArrayList<>());

		// 获取 OverAllState
		OverAllState state = getOverAllState(toolContext);

		// 注册到 Session 级别存储（优先）
		if (state != null) {
			SessionCodeManager.registerSessionFunction(state, code);
			logger.info("WriteConditionCodeTool#registerCode - reason=条件代码已注册到session, functionName={}",
					request.functionName);
		} else {
			// 降级：如果无法获取state，仍然注册到共享的CodeContext
			codeContext.registerFunction(code);
			logger.warn("WriteConditionCodeTool#registerCode - reason=无法获取state降级到全局CodeContext, functionName={}",
					request.functionName);
		}
	}

	/**
	 * 从ToolContext获取OverAllState
	 */
	private OverAllState getOverAllState(ToolContext toolContext) {
		if (toolContext == null || toolContext.getContext() == null) {
			return null;
		}
		Object state = toolContext.getContext().get(ToolContextConstants.AGENT_STATE_CONTEXT_KEY);
		if (state instanceof OverAllState) {
			return (OverAllState) state;
		}
		return null;
	}

	/**
	 * write_condition_code 工具的详细描述
	 * 
	 * <p>包含条件函数编写规范和指导，帮助 LLM 生成正确的条件判断代码。
	 */
	private static final String WRITE_CONDITION_CODE_DESCRIPTION = """
		Register a condition check function for triggers. The function MUST return a boolean value (True/False).
		
		REQUIRED PARAMETERS:
		- functionName: The exact function name (should start with 'check_' or 'condition_')
		- code: Complete Python function code that returns True or False
		- parameters: List of parameter names (optional, can be empty for timer-based conditions)
		- description: Natural language description of what condition this function checks
		
		CONDITION FUNCTION GUIDELINES:
		1. Return Type:
		   - MUST return True or False (boolean)
		   - True means condition is met, trigger action will execute
		   - False means condition is not met, no action
		
		2. For Timer/Delay-based Triggers:
		   - Condition function can simply return True
		   - Actual timing is controlled by subscribe_trigger's delay/cron parameter
		   - Example: def check_reminder_condition(): return True
		
		3. For Event-based Triggers:
		   - Check actual conditions (e.g., data changes, thresholds)
		   - Example: def check_quota_exceeded(current, limit): return current > limit
		
		4. Error Handling:
		   - Return False if any error occurs (fail-safe)
		   - Don't raise exceptions in condition functions
		
		EXAMPLES:
		
		1. Timer-based (always True, timing via delay):
		   write_condition_code(
		       functionName='check_medicine_reminder',
		       code='def check_medicine_reminder():\\n    return True',
		       parameters=[],
		       description='Timer condition for medicine reminder'
		   )
		
		2. Event-based (actual condition check):
		   write_condition_code(
		       functionName='check_is_working_day',
		       code='def check_is_working_day(date):\\n    return date.weekday() < 5',
		       parameters=['date'],
		       description='Check if the given date is a working day'
		   )
		""";

	/**
	 * 创建 ToolCallback
	 */
	public static ToolCallback createWriteConditionCodeToolCallback(
			CodeContext codeContext,
			RuntimeEnvironmentManager environmentManager) {

		WriteConditionCodeTool tool = new WriteConditionCodeTool(codeContext, environmentManager);

		return FunctionToolCallback.builder("write_condition_code", tool)
				.description(WRITE_CONDITION_CODE_DESCRIPTION)
				.inputType(Request.class)
				.build();
	}

	public static ToolCallback createWriteConditionCodeToolCallback(
			CodeContext codeContext,
			RuntimeEnvironmentManager environmentManager,
			CodeFastIntentSupport codeFastIntentSupport) {

		WriteConditionCodeTool tool = new WriteConditionCodeTool(codeContext, environmentManager, codeFastIntentSupport);

		return FunctionToolCallback.builder("write_condition_code", tool)
				.description(WRITE_CONDITION_CODE_DESCRIPTION)
				.inputType(Request.class)
				.build();
	}

	/**
	 * Request for writing condition code
	 */
	public static class Request {
		@JsonProperty(required = true)
		@JsonPropertyDescription("Function name for the condition check (should start with 'check_' or 'condition_')")
		public String functionName;

		@JsonProperty(required = true)
		@JsonPropertyDescription("Complete Python code that defines the condition function. Must return True or False.")
		public String code;

		@JsonProperty
		@JsonPropertyDescription("List of parameter names the condition function needs")
		public List<String> parameters;

		@JsonProperty
		@JsonPropertyDescription("Natural language description of what condition this function checks")
		public String description;

		public Request() {
		}

		public Request(String functionName, String code, List<String> parameters, String description) {
			this.functionName = functionName;
			this.code = code;
			this.parameters = parameters;
			this.description = description;
		}
	}
}

