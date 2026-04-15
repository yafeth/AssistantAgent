# 学习和经验模块设计文档

## 模块架构总览

Assistant Agent 框架的学习和经验模块是完整的**闭环学习系统**，包含以下核心组件：

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│   Learning      │────▶│   Experience    │────▶│   Skill Exchange│
│   (学习模块)    │     │   (经验模块)    │     │   (技能交换)    │
└─────────────────┘     └─────────────────┘     └─────────────────┘
         ▲                      │                           │
         └──────────────────────┴───────────────────────────┘
```

---

## 学习模块（Learning）

**核心文件**：`assistant-agent-extensions/src/main/java/com/alibaba/assistant/agent/extension/learning/extractor/ExperienceLearningExtractor.java`

### 核心能力

1. **智能判断**：LLM 判断是否值得学习，避免噪音
2. **自动提取**：从对话/工具调用/代码中提取结构化经验
3. **多种学习时机**：
   - Agent 执行后 (`AfterAgentLearningHook`)
   - 模型调用后 (`AfterModelLearningHook`) 
   - 工具调用后 (`LearningToolInterceptor`)

### 关键流程（shouldLearn + extract 二阶段）

```java
// 1. 判断阶段：先基础检查，再LLM判断
shouldLearn(context) → llmJudgeWorthLearning() → YES/NO

// 2. 提取阶段：LLM智能提取结构化经验  
extract(context) → llmExtractExperiences() → List<Experience>
```

### 提取的经验类型

| 类型 | 说明 | 示例 |
|-----|------|------|
| COMMON | 通用知识、需求理解、最佳实践 | "用户偏好简洁代码" |
| REACT | 多步策略、决策流程、任务编排 | "先分析再编码的模式" |
| TOOL | 工具使用前提、调用边界 | "Git操作前先检查状态" |

### LLM 提示词设计

**判断是否值得学习**：
- 判断标准：成功解决问题、有效方法、可复用代码、明确需求和方案 → 值得学习
- 排除标准：执行失败、简单问候、无实质内容、用户取消 → 不值得学习

**提取经验**：
- 要求提取有价值、可复用的经验
- 内容简洁、结构化，避免冗长
- 提炼通用模式和方法，不要包含具体对话细节

---

## 经验模块（Experience）

**核心模型**：`assistant-agent-extensions/src/main/java/com/alibaba/assistant/agent/extension/experience/model/Experience.java`

### Experience 数据结构

```java
Experience {
  id, name, description, content        // 基础信息
  type: COMMON|REACT|TOOL               // 经验类型
  disclosureStrategy: DIRECT|SEARCH|HIDE // 披露策略
  associatedTools, relatedExperiences    // 关联关系
  fastIntentConfig                        // 快速意图配置
  artifact                                // 可执行产物
  tags, metadata                          // 扩展信息
}
```

### 渐进式披露机制

**核心服务**：`ExperienceDisclosureService.java`

| 层级 | 披露方式 | 使用场景 |
|-----|---------|---------|
| Level 1 | Prefetch 候选卡片 | 上下文预加载，展示摘要 |
| Level 2 | `search_exp` 工具 | 主动搜索，获取详情 |
| Level 3 | `read_exp` 工具 | 按需读取完整内容 |
| Direct | 直接注入 Prompt | 高置信度经验直接可用 |

#### 直接披露条件

- 披露策略必须为 `DIRECT`
- 内容长度 ≤ 500 字符
- 置信度 ≥ 0.8（或无置信度评分）

### 快速意图（FastIntent）

- 基于经验实现的**FastPath**，跳过常规推理
- 配置匹配条件后直接执行经验中的动作
- 提升熟悉场景的响应速度

---

## 完整工作流

```
┌─────────────┐
│  Agent执行   │
└──────┬──────┘
       │
       ▼
┌─────────────────┐
│ Learning Hook   │ 捕获执行上下文
└──────┬──────────┘
       │
       ▼
┌───────────────────────┐
│ ExperienceLearning    │ LLM判断&提取
│ Extractor             │
└──────┬────────────────┘
       │
       ▼
┌──────────────────┐
│ Experience Store │ 持久化存储
└──────┬───────────┘
       │
       ▼
┌─────────────────────────┐
│ Experience Disclosure   │ 渐进式披露
│ & FastIntent            │
└──────┬──────────────────┘
       │
       ▼
┌─────────────┐
│ 下次Agent请求 │ ◀─── 经验应用
└─────────────┘
```

---

## 设计亮点

1. **LLM驱动的学习**：不是简单保存对话，而是智能提取可复用经验
2. **SKILLS标准对齐**：字段命名、披露策略对齐SKILLS规范
3. **多租户支持**：`ExperienceQueryContext`支持租户隔离
4. **异步学习**：`AsyncLearningHandler`避免阻塞主流程
5. **可扩展SPI**：`ExperienceProvider`、`LearningExtractor`等SPI接口

---

## 关键文件速查

| 组件 | 文件路径 |
|-----|---------|
| 经验提取器 | `assistant-agent-extensions/src/main/java/com/alibaba/assistant/agent/extension/learning/extractor/ExperienceLearningExtractor.java` |
| 经验模型 | `assistant-agent-extensions/src/main/java/com/alibaba/assistant/agent/extension/experience/model/Experience.java` |
| 披露服务 | `assistant-agent-extensions/src/main/java/com/alibaba/assistant/agent/extension/experience/disclosure/ExperienceDisclosureService.java` |
| 快速意图 | `assistant-agent-extensions/src/main/java/com/alibaba/assistant/agent/extension/experience/fastintent/FastIntentService.java` |
| 管理API | `assistant-agent-management/src/main/java/com/alibaba/assistant/agent/management/controller/ExperienceManagementController.java` |
| 学习执行器 | `assistant-agent-extensions/src/main/java/com/alibaba/assistant/agent/extension/learning/internal/DefaultLearningExecutor.java` |
| 内存经验存储 | `assistant-agent-extensions/src/main/java/com/alibaba/assistant/agent/extension/experience/internal/InMemoryExperienceRepository.java` |

---

## 包结构

### Learning 模块
```
com.alibaba.assistant.agent.extension.learning
├── config/          # 自动配置
├── extractor/       # 经验提取器
├── hook/            # 学习钩子
├── interceptor/     # 工具调用拦截器
├── internal/        # 内部实现
├── model/           # 数据模型
├── offline/         # 离线学习
├── repository/      # 仓储
└── spi/             # 扩展接口
```

### Experience 模块
```
com.alibaba.assistant.agent.extension.experience
├── config/          # 自动配置
├── disclosure/      # 渐进式披露
├── fastintent/      # 快速意图
├── hook/            # 钩子
├── internal/        # 内部实现
├── model/           # 数据模型
├── spi/             # 扩展接口
└── tool/            # 工具相关
```
