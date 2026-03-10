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
import com.alibaba.cloud.ai.graph.store.Store;
import com.alibaba.cloud.ai.graph.store.StoreItem;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * WriteCodeTool - 代码注册工具
 *
 * <p>职责（4.1 重构后）：
 * <ul>
 * <li>接收 LLM 在 React 阶段直接生成的完整 Python 代码</li>
 * <li>验证代码格式（函数名、参数是否匹配）</li>
 * <li>将代码注入到 CodeContext，供后续 execute_code 调用</li>
 * </ul>
 *
 * <p>改造说明：
 * <ul>
 * <li>取消了对 CodeGeneratorSubAgent 的委托调用</li>
 * <li>新增 code 参数，直接接收完整的 Python 函数代码</li>
 * <li>保留 FastIntent 快速匹配能力</li>
 * </ul>
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public class WriteCodeTool implements BiFunction<WriteCodeTool.Request, ToolContext, String> {

	private static final Logger logger = LoggerFactory.getLogger(WriteCodeTool.class);

	private final CodeContext codeContext;
	private final RuntimeEnvironmentManager environmentManager;

	private final CodeFastIntentSupport codeFastIntentSupport;

	public WriteCodeTool(CodeContext codeContext,
						 RuntimeEnvironmentManager environmentManager,
						 CodeFastIntentSupport codeFastIntentSupport) {
		this.codeContext = codeContext;
		this.environmentManager = environmentManager;
		this.codeFastIntentSupport = codeFastIntentSupport;
	}

	public WriteCodeTool(CodeContext codeContext, RuntimeEnvironmentManager environmentManager) {
		this(codeContext, environmentManager, null);
	}

	@Override
	public String apply(Request request, ToolContext toolContext) {
		logger.info("WriteCodeTool#apply 注册代码: functionName={}", request.functionName);

		try {
			// 0. FastPath Intent (CODE): hit => skip code validation
			String fastIntentResult = tryFastIntent(request, toolContext);
			if (fastIntentResult != null) {
				return fastIntentResult;
			}

			// 1. 验证必填参数
			if (request.functionName == null || request.functionName.trim().isEmpty()) {
				return "Error: functionName is required";
			}
			if (request.code == null || request.code.trim().isEmpty()) {
				return "Error: code is required. Please provide the complete Python function code.";
			}

			// 2. 清理代码（移除可能的 markdown 标记）
			String cleanedCode = cleanUpCode(request.code);

			// 3. 验证代码格式
			String validationError = validateCode(request.functionName, request.parameters, cleanedCode);
			if (validationError != null) {
				logger.warn("WriteCodeTool#apply 代码验证失败: {}", validationError);
				return "Error: " + validationError;
			}

			// 4. 注册代码到 CodeContext
			registerCode(request, cleanedCode, toolContext);

			logger.info("WriteCodeTool#apply 代码注册成功: functionName={}", request.functionName);
			return String.format("函数 %s 已成功注册，可以通过 execute_code 执行。\n```python\n%s\n```",
					request.functionName, cleanedCode);

		} catch (Exception e) {
			logger.error("WriteCodeTool#apply 代码注册失败", e);
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
			try {
				registerCode(request, code, toolContext);
			} catch (Exception e) {
				String err = e.getMessage();
				logger.warn("WriteCodeTool#tryFastIntent - reason=fast-intent register failed, expId={}, error={}",
						best.getId(), err);

				FastIntentConfig.FastIntentFallback fb = CodeFastIntentSupport.getOnRegisterFallback(best);
				if (fb == FastIntentConfig.FastIntentFallback.FAIL_FAST) {
					return "Error: FastIntent(Code) register failed: " + err;
				}
				return null;
			}

			logger.info("WriteCodeTool#tryFastIntent - reason=fast-intent HIT (skip codegen), expId={}", best.getId());
			return "FastIntent(Code) hit: " + best.getTitle() + "\n```python\n" + code + "\n```";

		} catch (Exception e) {
			logger.warn("WriteCodeTool#tryFastIntent - reason=fast-intent failed, fallback to normal flow, error={}", e.getMessage());
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
	 *
	 * @param expectedFunctionName 期望的函数名
	 * @param expectedParameters 期望的参数列表
	 * @param code 代码内容
	 * @return 错误信息，如果验证通过返回 null
	 */
	private String validateCode(String expectedFunctionName, List<String> expectedParameters, String code) {
		String actualFunctionName = environmentManager.extractFunctionName(code);
		if (actualFunctionName == null) {
			return "Code does not contain a valid function definition. Expected: def " + expectedFunctionName + "(...)";
		}
		if (!actualFunctionName.equals(expectedFunctionName)) {
			return String.format("Function name mismatch. Expected: %s, Actual: %s",
					expectedFunctionName, actualFunctionName);
		}
		return null;
	}

	/**
	 * 注册代码到 Session状态 和 Store
	 */
	private void registerCode(Request request, String code, ToolContext toolContext) {
		GeneratedCode generatedCode = new GeneratedCode(
				request.functionName,
				codeContext.getLanguage(),
				code,
				request.description != null ? request.description : ""
		);
		generatedCode.setParameters(request.parameters != null ? new ArrayList<>(request.parameters) : new ArrayList<>());

		OverAllState state = getOverAllState(toolContext);

		if (state != null) {
			SessionCodeManager.registerSessionFunction(state, generatedCode);
			logger.info("WriteCodeTool#registerCode - reason=代码已注册到session, functionName={}",
					request.functionName);
		} else {
			codeContext.registerFunction(generatedCode);
			logger.warn("WriteCodeTool#registerCode - reason=无法获取state降级到全局CodeContext, functionName={}",
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
	 * 保存到 Store
	 */
	@SuppressWarnings("unused")
	private void saveToStore(ToolContext toolContext, GeneratedCode code) {
		try {
			OverAllState state = (OverAllState) toolContext.getContext()
					.get(ToolContextConstants.AGENT_STATE_CONTEXT_KEY);
			if (state == null) {
				return;
			}

			Store store = state.getStore();
			if (store == null) {
				logger.warn("WriteCodeTool#saveToStore Store未初始化");
				return;
			}

			List<String> namespace = List.of("codeact", "code_generation");
			Map<String, Object> data = new HashMap<>();
			data.put("functionName", code.getFunctionName());
			data.put("language", code.getLanguage().name());
			data.put("code", code.getCode());
			data.put("parameters", code.getParameters());
			data.put("requirement", code.getOriginalQuery());

			StoreItem item = StoreItem.of(namespace, code.getFunctionName(), data);
			store.putItem(item);

			logger.debug("WriteCodeTool#saveToStore 代码已保存到Store: functionName={}", code.getFunctionName());

		} catch (Exception e) {
			logger.error("WriteCodeTool#saveToStore 保存失败", e);
		}
	}

	/**
	 * write_code 工具的详细描述
	 * 
	 * <p>包含代码编写规范和指导，帮助 LLM 生成正确的代码。
	 * <p>注意：此描述应保持通用，不预设具体的工具方法名，具体可用工具由运行时注入的 CodeactTool 决定。
	 */
	private static final String WRITE_CODE_DESCRIPTION = """
		Register a Python function with the specified name and complete code.
		
		REQUIRED PARAMETERS:
		- functionName: The exact function name (snake_case format, e.g., 'calculate_sum')
		- description: Brief description of what the function does
		- parameters: List of parameter names the function accepts
		- code: Complete Python function code including 'def' statement and full implementation
		
		CODE WRITING GUIDELINES:
		1. Function Structure:
		   - Must start with 'def function_name(params):' matching the functionName parameter
		   - Include docstring describing the function's purpose
		   - Handle errors gracefully with try/except blocks
		   - Return meaningful results as a dictionary
		
		2. Pure Computation Functions:
		   - For simple calculations or data processing, just return the result
		   - The agent will handle displaying results to the user after execute_code
		   - Example: return {"success": True, "result": computed_value}
		
		3. Tool Usage (when tools are available):
		   - Tools are injected at runtime as Python objects
		   - Use 'tool_class.method_name(args)' format to call tools
		   - Check available tools in the system prompt or guidance before using them
		   - Common tool classes: search_tools, reply_tools, trigger_tools, etc.
		   - DO NOT assume specific method names exist - verify first!
		
		4. Error Handling:
		   - Wrap risky operations in try/except blocks
		   - Return error information when operations fail
		   - Example: except Exception as e: return {"success": False, "error": str(e)}
		
		IMPORTANT NOTES:
		- The code runs in a GraalVM Python sandbox environment
		- Only use tools that are explicitly available in the current session
		- When in doubt about available tools, write pure Python code that returns results
		- The agent can display results to users after code execution
		
		EXAMPLE (Pure Computation):
		write_code(
		    functionName='calculate_sum',
		    description='Calculate the sum of two numbers',
		    parameters=['a', 'b'],
		    code='''def calculate_sum(a, b):
		    \"\"\"Calculate the sum of two numbers\"\"\"
		    try:
		        result = a + b
		        return {"success": True, "sum": result, "message": f"{a} + {b} = {result}"}
		    except Exception as e:
		        return {"success": False, "error": str(e)}
		'''
		)
		""";

	/**
	 * 创建 ToolCallback
	 */
	public static ToolCallback createWriteCodeToolCallback(
			CodeContext codeContext,
			RuntimeEnvironmentManager environmentManager,
			CodeFastIntentSupport codeFastIntentSupport) {

		WriteCodeTool tool = new WriteCodeTool(codeContext, environmentManager, codeFastIntentSupport);

		return FunctionToolCallback.builder("write_code", tool)
				.description(WRITE_CODE_DESCRIPTION)
				.inputType(Request.class)
				.build();
	}

	public static ToolCallback createWriteCodeToolCallback(
			CodeContext codeContext,
			RuntimeEnvironmentManager environmentManager) {
		return createWriteCodeToolCallback(codeContext, environmentManager, null);
	}

	/**
	 * Request for writing code
	 */
	public static class Request {
		@JsonProperty(required = true)
		@JsonPropertyDescription("The exact name of the function to register (snake_case format)")
		public String functionName;

		@JsonProperty(required = true)
		@JsonPropertyDescription("Description of what the function does")
		public String description;

		@JsonProperty
		@JsonPropertyDescription("List of parameter names the function accepts")
		public List<String> parameters;

		@JsonProperty(required = true)
		@JsonPropertyDescription("Complete Python function code, including 'def' statement and implementation")
		public String code;

		public Request() {
		}

		public Request(String functionName, String description, List<String> parameters, String code) {
			this.functionName = functionName;
			this.description = description;
			this.parameters = parameters;
			this.code = code;
		}
	}
}

