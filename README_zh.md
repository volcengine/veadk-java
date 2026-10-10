# veadk-java

一款集成火山引擎强大能力的开源智能体（Agent）开发工具包。

## 快速开始

### 环境要求
- JDK `17` 或以上版本

```xml
<dependency>
    <groupId>com.volcengine.veadk</groupId>
    <artifactId>veadk-java</artifactId>
    <version>0.0.2</version>
</dependency>
```

### Agent

建议优先使用 `Agent.builder()` 作为 VeADK 的 Java 入口。它仍复用 ADK Java
`LlmAgent` 的执行链路，同时补充 VeADK 默认值和可被集成侧读取的 metadata。

```java
import com.volcengine.veadk.Agent;

Agent agent = Agent.builder()
    .name("quickstart-agent")
    .description("回答用户问题。")
    .instruction("你是一个有帮助的助手。")
    .modelName("doubao-seed-2-1-pro-260628")
    .modelApiKey(System.getenv("MODEL_AGENT_API_KEY"))
    .build();
```

`modelName(...)` 会在 build 阶段创建默认 `ArkLlm`。可以通过
`modelApiKey(...)` 显式传入 Ark API key；如果不传，则从环境变量解析模型凭证。
需要自定义 Ark endpoint 时可使用
`modelApiBase(...)` / `modelBaseUrl(...)`。

如果你希望自己控制模型配置，也可以直接传入 ADK `BaseLlm` 实例：

```java
import com.google.adk.models.BaseLlm;
import com.volcengine.veadk.Agent;
import com.volcengine.veadk.model.ArkLlm;

BaseLlm model = new ArkLlm("doubao-seed-2-1-pro-260628");

Agent agent = Agent.builder()
    .name("custom-model-agent")
    .instruction("你是一个有帮助的助手。")
    .model(model)
    .build();
```

metadata 通过类型化 extractor 输出。VeADK `Agent` 会读取
`Agent.metadataSnapshot()`；普通 ADK `LlmAgent` 会降级使用 ADK public getter。

```java
import com.volcengine.veadk.AgentMetadata;
import com.volcengine.veadk.AgentMetadataExtractor;

AgentMetadata metadata = AgentMetadataExtractor.extract(agent);
System.out.println(metadata.tools());
```

如果需要简单的阻塞式交互入口，可以使用 VeADK runner 便捷 API：

```java
import com.volcengine.veadk.Runner;

String answer = new Runner(agent).run("你好");
```

#### 本地 Skills

本地 skills 通过 ADK Java 的 `SkillToolset` 支持。配置
`Agent.builder().skills(...)` 后，VeADK Java 会自动注入一个 `SkillToolset`；如果
用户已经通过 `tools(...)` 显式传入 `SkillToolset`，则不会重复注入。

一个 skill 是包含必需 `SKILL.md` 文件的目录：

```text
skills/
  expense-policy-reviewer/
    SKILL.md
    references/
    assets/
    scripts/
```

`SKILL.md` 必须以 frontmatter 开头，且其中的 `name` 需要与 skill 目录名一致：

```markdown
---
name: expense-policy-reviewer
description: 根据公司制度审核员工报销申请。
---

根据报销制度进行判断，并输出简洁的预审结论。
```

可以传入 skills 根目录、单个 skill 目录、`SKILL.md`/`skill.md` 文件，或显式的 ADK
`SkillSource`：

```java
import java.nio.file.Path;

Agent agent = Agent.builder()
    .name("expense-review-agent")
    .instruction("回答制度问题前先使用本地 skills。")
    .modelName("doubao-seed-2-1-pro-260628")
    .skills(Path.of("example/src/main/resources/skills"))
    .skillsMode("local")
    .build();
```

`skills(...)` 支持以下条目：

- `Path` 或 `String`：本地文件系统路径。包含 `SKILL.md` 或 `skill.md` 的目录会被
  视为单个 skill；否则该目录会被视为包含多个 skill 子目录的 skills root。
- `SkillSource`：任意 ADK Java skill source，例如 `LocalSkillSource` 或
  `ClassPathSkillSource`。

多个 skill 条目会按传入顺序合并。如果多个条目暴露同名 skill，后传入的条目在
`list_skills` 和 `load_skill` 中都会覆盖先传入的条目。

如果 skill 打包在应用 resources/classpath 中，可以显式传入 ADK 的 classpath source：

```java
import com.google.adk.skills.ClassPathSkillSource;

Agent agent = Agent.builder()
    .name("classpath-skill-agent")
    .skills(new ClassPathSkillSource("skills"))
    .skillsMode("local")
    .build();
```

远端 skill source 的元信息和 skill 包可以通过 `VeSkillSource` 暴露给 ADK Java
`SkillToolset`。source ID 可以是 AgentKit Skill Space（`ss-...`），也可以是
SkillHub space（`sp-...`）：

```java
import com.google.adk.tools.skills.SkillToolset;
import com.volcengine.veadk.skills.VeSkillSource;

Agent agent = Agent.builder()
    .name("remote-skill-agent")
    .tools(new SkillToolset(new VeSkillSource(System.getenv("SKILL_SOURCE_ID"))))
    .build();
```

如果要委托 Skills Sandbox 执行，传入 AgentKit Skill Space ID，并只暴露 `execute_skills`
工具：

```java
import com.volcengine.veadk.tools.builtin.sandbox.ExecuteSkillsTool;

Agent agent = Agent.builder()
    .name("remote-sandbox-agent")
    .skills(System.getenv("SKILL_SPACE_ID"))
    .skillsMode("skills_sandbox")
    .tools(new ExecuteSkillsTool())
    .build();
```

Sandbox 工具调用失败时会返回结构化错误。Agent 可以读取 `error.code`、
`error.message`、`error.suggestion` 和 `error.retryable` 来解释失败原因或判断是否重试：

```json
{
  "error": {
    "code": "SKILLS_SANDBOX_A2A_FAILED",
    "message": "message/send failed: invalid skill request",
    "suggestion": "Check the Skills Sandbox A2A error message and retry only if the error is transient.",
    "retryable": false
  }
}
```

metadata 输出包含：

- Agent 基础字段：`id`、`name`、`description`、`instructionSummary`、`modelName`、
  `autoSaveSession`。
- `tools`：工具名及来源。`explicit` 表示用户通过
  `Agent.builder().tools(...)` 传入；`auto` 表示 builder 根据 VeADK 组件自动注入，
  例如 `loadKnowledgebase`、`loadMemory` 或 `SkillToolset`；`adk` 表示从普通
  ADK `LlmAgent` 降级提取。
- `subAgents`：每个子 Agent 使用同样的 metadata 结构。
- `components`：稳定组件槽位，包括 `knowledgebase`、`longTermMemory`、
  `shortTermMemory`、`tracer`、`toolset`、`plugin`。
- `searchSources`：`web`、`knowledge`、`memory`，每项包含 `enabled` 状态和对应工具名。
  规范工具名是 `web_search`、`loadKnowledgebase`、`loadMemory`。

### 模型凭证
实例化或调用 `ArkLlm` 的示例需要模型凭证，解析顺序如下：

- `modelApiKey(...)`：builder 上显式传入的 API key。
- `MODEL_AGENT_API_KEY`：环境变量中的 Ark 原始 API key。
- `MODEL_AGENT_API_KEY_NAME`：Ark API key 名称。配置后，VeADK 会通过 Ark OpenAPI
  解析出原始 key。
- 火山 AK/SK fallback：未配置 key 值或 key 名称时，VeADK 会通过 Ark OpenAPI 解析账号
  下的第一个 Ark API key。

Ark OpenAPI fallback 需要：

- `VOLCENGINE_ACCESS_KEY`
- `VOLCENGINE_SECRET_KEY`
- 可选：`VOLCENGINE_SESSION_TOKEN` 或 `VOLC_SESSIONTOKEN`
- 可选：`REGION`，默认 `cn-beijing`
- 可选：`CLOUD_PROVIDER=byteplus`，用于 BytePlus 控制面路由
 
示例设置（macOS / Linux）：

```bash
export MODEL_AGENT_API_KEY="<your-ark-api-key>"
```

## 运行项目示例

### 构建项目
在仓库根目录执行：`./mvnw clean -DskipTests package`

构建完成后，`example/target` 会生成示例所需的编译产物。

运行 Agent 示例。它会构建一个使用 Ark 模型的 `Agent`，注册一个 Java 函数工具，并直接
调用 `Runner.run(...)`：

```bash
export MODEL_AGENT_API_KEY="<your-ark-api-key>"
./mvnw -pl example -am -q compile exec:java -Dexec.mainClass=com.volcengine.veadk.example.AgentExample
```

运行本地 skills 示例。它会从 `example/src/main/resources/skills` 加载报销制度
skill，并对一笔真实风格的报销申请做预审：

```bash
export MODEL_AGENT_API_KEY="<your-ark-api-key>"
./mvnw -pl example -am -q compile exec:java -Dexec.mainClass=com.volcengine.veadk.example.LocalSkillsExpenseReviewAgent
```

### 运行示例（CLI）
示例入口：`com.volcengine.veadk.example.AgentCliRunner`。

运行方式（无需修改 POM，直接通过 Maven Exec 插件坐标）：

```bash
./mvnw -pl example -am -q compile exec:java -Dexec.mainClass=com.volcengine.veadk.example.AgentCliRunner
```

交互说明：
- 启动后按提示输入对话内容，与 `ArkAgent` 进行交互。
- 输入 `quit` 退出。

### 运行示例（Web UI）
启动命令：

```bash
./mvnw -pl example -am -q compile exec:java \
    -Dexec.mainClass="com.google.adk.web.AdkWebServer" \
    -Dexec.args="--adk.agents.source-dir=example/target --server.port=8000"  
```

- 访问地址：`http://localhost:8000`

### 在 IDE 中运行
- 使用 IntelliJ IDEA 或 Eclipse 导入 Maven 多模块工程。
- 直接运行 `AgentCliRunner` 或 `AdkWeb` 的 `main` 方法即可。
- 确保在 IDE 的运行配置中注入必需环境变量（或使用 shell 启动 IDE）。
- 如果需要使用web search、viking memory、viking knowledgebase，需要配置环境变化：
  - VOLCENGINE_ACCESS_KEY：火山引擎AccessKey
  - VOLCENGINE_SECRET_KEY：火山引擎SecretKey
- 如果需要使用 Mem0 Memory，可以直接配置 Mem0 API Key：
  - `DATABASE_MEM0_API_KEY`：Mem0 API Key
  - `DATABASE_MEM0_BASE_URL`：Mem0 服务地址，例如 `https://api.mem0.ai`
- 也可以配置火山 AK/SK 和 Mem0 项目信息，由 VeADK 自动解析 Mem0 API Key：
  - `VOLCENGINE_ACCESS_KEY`：火山引擎 AccessKey
  - `VOLCENGINE_SECRET_KEY`：火山引擎 SecretKey
  - `REGION`：火山引擎 Region，默认 `cn-beijing`
  - `DATABASE_MEM0_PROJECT_ID`：Mem0 记忆项目 ID
  - `DATABASE_MEM0_API_KEY_ID`：可选，Mem0 API Key ID
- 如果需要使用TLS Trace，除了AK/SK，还需要配置TLS Topic
  - OBSERVABILITY_OPENTELEMETRY_TLS_SERVICE_NAME：TLS服务trace日志主题的id

运行 Mem0 记忆示例：

```bash
./mvnw -q install -DskipTests
./mvnw -pl example -am -q compile exec:java -Dexec.mainClass=com.volcengine.veadk.example.Mem0MemoryAgent
```

## 相关项目
- Python 版本与文档参考：[veadk-python](https://github.com/volcengine/veadk-python)。

## 当前功能范围

Java Agent 当前提供小而稳定的类型化契约。它在用户入口上对齐 Python 版本
（`Agent.builder()`、模型、工具、sub-agents、memory/knowledgebase metadata），但
metadata 来自 builder 阶段记录的显式状态和 ADK public getter，不通过运行时反射扫
对象内部字段。

Java Agent 当前不支持以下 Python 侧能力：

- `runtime=codex/piagent`
- `enableResponses`
- `aio_sandbox`
- `enableA2ui`
- `enableTunnel`
- YAML 或动态工具发现

## 常见问题
- 启动时报错 `Missing required configuration: <ENV_NAME>`：表示必需环境变量未设置，请根据提示进行补全。
- 无法访问内存/知识库/搜索服务：请检查 AK/SK 与网络连通性。
- 端口占用：如 `8000` 端口被占用，可通过`--server.port`或者`AdkWeb.main`中调整启动参数。

## 许可证
本项目使用 Apache License 2.0，详见 `LICENSE` 文件。
