# Review Summary

**本次需求：为 VeADK Java 的 Ark Chat Completions 流式调用补齐 SDK 接收侧 TPOT Span 属性与 Histogram，保持与 Python MR #1223 相同的指标含义。**

- 需求来源：GitHub MR 同步；优先级 P3；关联 Meego story 7398797820。
- 需求类型：Model / Observability / SDK 指标契约。重点评审计时位置、上游正常终态、最终 Token 计数范围、清理成功及一次计量。
- 变更面依据：ArkLlm 接收 SDK chunk 后缓冲文本；OpenTelemetry 支持 MetricReader；官方 ADK 与 Ark SDK 未提供本契约的 TPOT 指标。
- 差异判断：涉及 Python / Java SDK 流生命周期和响应映射差异；地域及站点契约无变化；依赖兼容版本矩阵由维护者确认。
- 新增：请求级 TPOT；保守省略不可靠样本；指标采集独立于 Trace 采样。Java 现有任意 finish reason 检测不能直接用于 TPOT 成功判定。

## Scope Note

展开需求、SDK/指标契约、兼容与验收；范围限定 Ark Chat Completions。Java main 已有 Responses 和 OpenAI compatible 适配，但本次不扩展它们。无前端、Dashboard、CLI、业务持久化 schema、权限模型或历史数据迁移；不重做 TTFT，不移植 Python LiteLLM 内部结构。

## 1. 需求来源与目标

| 项目 | 内容 |
| --- | --- |
| 来源 | [Python MR #1223](https://github.com/volcengine/veadk-python/pull/1223)，feat(tracing): record streaming time per output token |
| 源基线 | 合并于 2026-11-18 09:25:19 UTC；1763e5a548a952449d87e0ccfc11c2ec8d78c8cc；7 文件，+909/-25 |
| 目标仓库 | volcengine/veadk-java；Model、Observability；ArkLlm.java、trace/OpenTelemetry.java 及现有 exporter 接入 |
| 调研基线 | 2026-11-20 fetch 后 origin/main = 4a5ed7f7f5ebb6d6f475995e3ad0904c5d8d295e，与输入基线一致 |
| 交付分支 | feat/d9ce411f-2lhv；基线 main；此节点交付文档，业务实现属于下游开发节点 |
| 共享上下文 | Context 4，task_detail，repository_resolution=resolved；Java base_branch=main |
| 人员 | 需求负责人/RD 单春华；PMO 丁彦俊；Meego 最新回读确认 create_by 为单春华；源 MR author wujiaming-ai，commit author 信息沿用输入 |

目标用户为排查模型流式生成性能的开发者和运维人员。现有请求总时长无法分离首输出前等待与生成阶段；本次让用户可以按相同模型指标比较 Java 与 Python 的生成速度。TPOT 是一次 SDK 观测调用的平均秒/输出 Token，并非客户端渲染速度。

## 2. 调研现状与验证

**同步判断：需要 Java 跟进。** TPOT 属于 VeADK 封装 ADK 与适配火山模型产品的核心观测能力，仅同步本 MR 的增量契约。[同步判断文档](sync-decision.md)记录差异与证据。

| 调研对象 | 当前行为与影响 |
| --- | --- |
| Java ArkLlm | Flowable.defer 每订阅分配累积状态；SDK RxJava 2 publisher 转为 RxJava 3；concatMap 内接收 chunk；文本超过 30 字符才发 partial，不能用 partial 输出时间计量 |
| usage | StreamOptions includeUsage=true；收到 usage 时覆盖保存最终累计值，最终映射 candidatesTokenCount；不能累加多个 usage 包，也不能以格式化后 ADK 响应计数估算 |
| 结束检测 | isStopChunk 仅判断 finishReason 非空；未收到 finish reason 会抛 IllegalStateException。length/filter 等业务终态与 TPOT 合格终态须分别判断，保留既有业务结果 |
| Java 遥测 | 已有 MetricReader 注册与属性重写 exporter；全仓没有 TPOT 字段或 Histogram；ArkLlm 中未发现 model_request / TTFT 采集。TTFT 本次不新增，存在时不得损坏 |
| 官方 ADK Java 1.11.0 | BaseLlm 使用 Flowable<LlmResponse>；Tracing 提供 per-subscription Span、context 传播及 doFinally 结束能力，但 doFinally 同时涵盖完成/异常/取消，不能视为成功。无本契约 TPOT，可复用现有 tracing 能力 |
| Ark SDK 0.2.48 | ChatCompletionChunk 包含 choice index、finishReason、message；ChatMessage 提供 reasoningContent、toolCalls；Usage.completionTokens 是 long，details 提供 reasoningTokens。SDK 原样累计 usage，可直接采用，勿重复实现 SDK |
| Ark 生命周期限制 | ResponseBodyCallback 在 emitter.onComplete 后才 reader.close，关闭 IOException 被吞掉；读取循环检查 emitter.isCancelled，create 流未见取消 hook 绑定 apiCall.cancel。消费完成/Flowable.doFinally 不自动证明 Reader 清理成功 |
| 未知字段限制 | ChatMessage 与 CompletionTokensDetails 忽略未知字段；0.2.48 details 未见 audio_tokens。若支持的响应可能含 SDK 丢弃的音频/摘要/隐藏推理，须证明计数范围一致，否则省略；不能仅以 Java 字段缺失推断上游没有该内容 |

本需求为功能同步缺口，不判定既有 SDK 生命周期行为为需一并修复的 Bug。本次验证走实际 VeADK Agent → Runner → ArkLlm → 官方 Ark SDK → 本地 HTTP SSE，并分别读取用户输出与 OTel 导出；源码来自最新 main，不修改实现。真实云调用在此节点跳过：可控服务可提供间隔、usage 与终态，且任务未指定真实模型端点/云资源；本地结果不能证明云端字段和清理可观测性。无 AgentKit 控制台交互，无控制台 agentId；本地标识 tpot_java_probe / probe-user / probe-session。

实际结果：Java 四项本地 E2E（正常、尾部 5 秒、length、缺终态）确认无 model_request/TPOT；正常/尾部/length 返回 hello world，缺终态传播 IllegalStateException；模型输出 Token=100 已出现在现有 call_llm。46 项 Java 相关基线回归通过；Python 源 MR 72 项契约测试通过。详细过程见附录。

## 3. 需求范围与行为

### REQ-001：请求级计量（P3）

作为开发者，我希望每次合格的流式模型 SDK 请求获得同一含义的 TPOT，以便跨语言排查生成性能。

- 当单候选流被完整消费，具有上游真实正常结束原因且本次请求流清理成功，至少收到两个有效输出增量，最终累计输出 Token 为大于 1 的整数且范围一致时，VeADK 应在该次 SDK 请求的 model_request Span 写入一次 TPOT，并向同名 Histogram 记录一次样本。
- Span 属性仅存在于 model_request，不能复制到父 Span；其语义是一观测调用，不能跨多个请求共用。现有 ADK call_llm Span 可作父层，不能用父层耗时代替 SDK 层计量。
- Trace recording 关闭/采样丢弃时，只要现有指标采集已启用，Histogram 仍应计量一次。采样丢弃的 Span 不要求导出属性。
- Histogram 标签仅 gen_ai.request.model=本次实际请求模型、gen_ai.operation.name=chat；单位 s，double 有限非负值。

### REQ-002：计时与分母（P3）

`TPOT = (最后有效输出增量接收时间 - 首个有效输出增量接收时间) / 1e9 / (最终累计 completion_tokens - 1)`，时间以纳秒举例，结果为浮点秒。

- 使用单调时钟。首末位置在官方 SDK chunk 接收侧、聚合或文本/工具缓冲之前；排除首输出前等待、尾部 usage、连接清理和后续工具执行。首末之间的网络、SDK 调度和消费等待按实际观测计入。
- 非空文本、完整推理增量及工具参数增量属于有效输出。单个 SDK 增量即使包含多个字段，也只能作为一次有效增量；role、空内容、纯 usage、纯结束标记均不推进末次时间。字段映射由研发基于官方 SDK 设计并测试。
- 采用上游最后可信的累计 completion_tokens；不累加 usage 包，不使用字符数、chunk 数或本地估算。Chat 总输出已经含推理 Token 时，不再加 reasoning_tokens。
- completion_tokens ≤1、非法值，reasoning_tokens 非整数/负数/超过总输出，或已知推理计数>0而未观察到完整推理时，省略。隐藏推理、推理摘要、音频或未知包装使计数范围无法确认时省略。
- 实测间隔为 0 时可记有效 0 秒；时钟反向、负值或非有限结果应省略。

### REQ-003：省略条件与终态（P3）

出现下列任一条件时，VeADK 应省略 TPOT 属性及 Histogram 样本，不能补 0 或采用总耗时：

| 类别 | 应分别验证的条件 |
| --- | --- |
| 调用/输出 | 非流式、无有效输出、仅一个有效增量、多候选（请求或响应任一体现）、缓存回放、未知 SDK 包装 |
| Token | usage 缺失/非法、计数范围不匹配、隐藏推理、仅摘要或音频输出、无法排除未知输出字段影响 |
| 生命周期 | 异常、超时、取消、主动终止、截断、过滤、缺少真实正常终态、清理失败或无法确认清理成功 |

正常 finish reason 的候选语义与 Python Chat 契约保持一致：stop、tool_calls、function_call；研发按 Ark SDK/上游实际支持值映射。length、content_filter 或未知值不合格。SDK/兼容层合成的 stop 不能作证。消费到自然结束、收到 [DONE] 或执行 doFinally 均不能独自证明真实正常终态与成功清理。

取消即使发生在已收到正常终态之后，只要请求未完成必要消费/清理，仍不计量。重复完成信号不能产生重复记录；不可信的重复终态、终态后再有输出须保守省略。已观测的 TTFT 保留，业务的 finish 状态与原有异常不改写。

### REQ-004：隔离与兼容（P3）

- 并发请求、重复订阅和 VeADK 可见重试按实际 SDK 调用隔离。失败尝试无 TPOT；后续合格尝试各计一次。SDK 内部隐藏重试仍是一观测调用，不假造独立尝试或拆分样本。
- 采集/导出失败不影响内容、增量顺序、工具参数、usage、结束状态、返回及原有异常传播；遥测不能发起新的模型调用、阻塞等待远端导出或泄漏后端失败详情。
- 沿用现有鉴权和遥测启用配置；不增加必填计时参数，不改变公开调用签名、输出格式、默认业务流程。日志及新增遥测不记录原始提示、生成文本、工具参数或凭据，不增加请求 ID 等高基数指标标签。
- 使用 Java 官方 ADK/Ark SDK/OTel API；禁止为本需求降级 ADK 或任何依赖；不强行对齐 Python 内部实现。

## 4. 验收标准与验证要求

**EARS 主验收：** 当合格单候选流的首末 SDK 有效增量间隔为 0.99 秒、最终累计 completion_tokens=100 时，VeADK 应得到 TPOT=0.01 秒，model_request 属性与 Histogram sum 中的单样本值一致，count=1。尾部 usage/清理增加 5 秒后仍为 0.01 秒。无正常终态或取消时无属性、无样本，保留原有返回/异常与已存在的 TTFT。

```gherkin
Scenario: 正常流式请求排除尾部延迟
  Given 单候选真实正常结束且清理成功的完整流，最终输出 Token 为 100
  And 至少两个有效增量且首末 SDK 接收间隔为 0.99 秒
  When 尾部 usage 和连接清理额外等待 5 秒
  Then model_request TPOT 为 0.01 秒且 Histogram 仅记录一次
Scenario: 请求取消
  Given 已收到两个有效增量
  When 流在完整消费与成功清理之前被取消
  Then 不产生 TPOT 属性或样本，且保留已有 TTFT 与原有取消行为
```

| 验证层 | 用例及通过标准 |
| --- | --- |
| 精确契约 | 内存 OTel 与内部可替换单调时钟；验证 0.99/99=0.01，尾部 5 秒不改变值；浮点绝对误差≤1e-12，不要求调用方新增时钟参数 |
| 增量映射 | 文本、完整推理、工具参数分别测；同 chunk 多字段只观察一次；role/空字段/usage/终态不推进时间；纯工具流不因 ADK 缓冲丢计时 |
| Token | 最终覆盖非累加；大于 1 的整数与有效 0 秒；REQ-002 的非法与范围不匹配条件分别省略 |
| 终态与失败 | REQ-003 每项分别测试；包含消费取消、收到终态后取消、异常与清理失败；无样本时必须验证属性也缺失 |
| 采样与导出 | alwaysOff Trace + active MetricReader：无采样 Span，Histogram count=1；故障注入覆盖时钟/Span/Histogram/exporter，业务内容及异常保持一致 |
| 隔离与重试 | 并发 A=0.01、B=0.02；独立 Span；分别计数；重试失败尝试无记录，成功尝试一次；重复结束/订阅不串状态 |
| 本地 E2E | 用户 Agent 与测试代码分开；HTTP SSE → 官方 SDK → VeADK → OTel reader/exporter，独立文件校验输出、usage、finish、父 Span 与样本。真实时间有调度误差，不用服务发送时间替代 SDK 接收时间 |
| 回归 | 现有 ArkLlmMappingTest、ArkResponsesTest、OpenTelemetryTest；标准构建/测试按仓库 CI 执行，记录依赖版本与失败原因 |
| 真实服务补验 | 发布前使用维护者批准的 Ark Chat 模型端点与遥测导出环境，核验纯文本、完整推理、工具调用及取消/清理；记录 endpoint/model 标识、版本、trace 标识，凭据仅走配置。无法确认范围/清理的流应省略，并记录省略覆盖率限制；若对接 AgentKit，补充控制台 agentId |

内存指标证明采集，现有 exporter 的集成验收证明导出。sum/count 是**按请求平均 TPOT**，不是按 Token 加权平均；不建设 Dashboard。

## 5. SDK 与同类框架调研结论

| 官方/参考来源 | 可复用能力 | 差异化要求 |
| --- | --- | --- |
| [ADK Java v1.11.0](https://github.com/google/adk-java/tree/v1.11.0/core/src/main/java/com/google/adk)；commit c8b1de6a1878ae2ac1c66d1e05fc30fc85b060b4 | Tracing、Flowable per-subscription 生命周期与上下文 | 利用现有 ADK 观测层级；额外区分正常完成/错误/取消，避免将 doFinally 解释为成功 |
| [Ark SDK 0.2.48 sources](https://maven.aliyun.com/repository/public/com/volcengine/volcengine-java-sdk-ark-runtime/0.2.48/volcengine-java-sdk-ark-runtime-0.2.48-sources.jar)（官方发布包经镜像下载） | 原始 chunk、reasoningContent、toolCalls、累计 usage | 在 SDK 接收位置观测；映射前核对真实计数范围；清理可观测性不得凭空推断 |
| [OpenTelemetry Java 1.62.0](https://github.com/open-telemetry/opentelemetry-java/tree/v1.62.0) | double Histogram、MeterProvider、MetricReader、Span | 使用现有指标管线，不自建统计/导出系统；Histogram 无需 sampled Span |
| [LangChain4j 参考](https://github.com/langchain4j/langchain4j/tree/c5f5d093fe14d7061570474bc6f5e3cb117ec155) | StreamingChatResponseHandler 区分文本/推理/工具回调和完成；ChatModelListener 分请求/响应/错误；Micrometer listener 统计请求耗时、Token | 借鉴请求隔离与完成/错误区分；partial callback 可能批量多 Token，不能以 chunk 数作分母；整次 operation.duration 不能冒充本契约 TPOT。仅作参考，未将其 main 行为视为仓库依赖版本能力 |

调研结论：复用官方 SDK 和 OTel；在 VeADK Ark 适配层补齐语言无关契约。Python LiteLLM 的 CustomStreamWrapper、received_finish_reason 和 retained chunks 不适用于 Java；Java 使用官方 DTO、Reactive Streams 生命周期与现有资源机制，由研发确定最小接入方案。

## 6. Open Questions、兼容发布与交付状态

| ID | 问题及处理边界 | 负责人/时点 |
| --- | --- | --- |
| OQ-001 | 核对 reasoningContent 是否完整、completion_tokens 是否含全部推理、未知字段丢弃如何影响范围；不能验证则按 REQ-003 省略，不扩展 Provider | Java 维护者、RD 单春华；开发设计与 E2E 前 |
| OQ-002 | SDK 0.2.48 完成先于 Reader.close 且关闭异常吞掉，取消未绑定 Call.cancel：确定可复用的清理成功证据/官方 SDK 能力；无法确认则省略，不以 doFinally 兜底；不降级依赖 | Java 维护者、RD 单春华；埋点设计前 |
| OQ-003 | 发布版本及 SDK 兼容验证矩阵，确认每个声明支持的版本均满足增量/usage/终态/清理可观测条件 | Java 维护者；发布前 |

这些事项不改变已确定的保守省略规则，不需在 PRD 阶段新增产品决策。研发需要公开实际可采集覆盖范围；不得为使验收通过而绕开资格判断。

新增指标契约，不补写历史数据，不迁移。按现有发布流程交付，维护者决定版本；回退新增采集应恢复既有业务与其他遥测。文档状态为需求设计产物，功能尚未实现/发布；本节点不将方案待设计标成开发完成。

## 7. 附录：可执行调研与验证

详细命令、独立用户程序、SSE fixture、观测测试程序、实际结果与限制见 [验证记录](validation.md)。以下附录在交付时附入同一 PRD 飞书正文，临时验证源码均在 /tmp，未提交到开发分支。


### 验证范围与环境

本记录是需求设计阶段的基线验证，证明当前 Java 缺口与 Python 源契约。没有为 Java 实现 TPOT，也没有把将来的验收矩阵记为已通过。

- 日期：2026-11-20；Linux x86_64；JDK 17.0.21.1；Apache Maven 3.6.3；Java main 4a5ed7f7f5ebb6d6f475995e3ad0904c5d8d295e。
- Java 声明依赖：google-adk 1.11.0、Ark SDK 0.2.48、OpenTelemetry 1.62.0；未修改或降级依赖。
- Python：源合并提交 1763e5a548a952449d87e0ccfc11c2ec8d78c8cc；Python 3.12；google-adk 2.11.0、LiteLLM 1.83.14、OpenTelemetry SDK 1.42.1、volcengine-python-sdk 5.0.56、pytest 9.2.0、pytest-asyncio 1.5.0。
- 独立用户程序 UserAgent.java；测试/观测 Probe.java；本地上游 sse_fixture.py；独立 verify.py 校验文件。临时源码只在 /tmp，Git 中仅保存此说明与代码块。
- Java 验证副本以 git archive origin/main 导出，所有构建输出在 /tmp/veadk-tpot-validation；用户程序通过真实 VeADK Agent/Runner 和官方 SDK 发起 HTTP 流式请求。
- 无真实云请求：受控上游用于固定增量、尾部延迟、usage 与 finish reason；任务没有提供指定云端模型端点。此验证不证明生产推理字段范围或清理可观测性；发布前真实服务补验见 PRD 第 4 节。
- 无 AgentKit 控制台交互；本地 Agent 名称 tpot_java_probe、user probe-user、session probe-session，不涉及控制台 agentId。

### 调研命令与证据

在工作区根目录运行：

```bash
agentnative workflow repositories status --available --json
cat repos/manifest.yaml artifacts/_manifest.json
cd repos/volcengine_veadk-java
git status --short --branch
git fetch origin main
git rev-parse HEAD origin/main
rg -n 'model_request|time_to_first|streaming_time_per_output_token|Histogram' core
rg -n 'usage|isStopChunk|concatMap|Flowable.defer' core/src/main/java/com/volcengine/veadk/model/ArkLlm.java
gh api repos/volcengine/veadk-python/pulls/1223 --jq '{merged,merged_at,merge_commit_sha,title,changed_files,additions,deletions}'
gh api repos/volcengine/veadk-python/pulls/1223/files --paginate --jq '.[] | {filename,patch}'
```

实际结果：共享集合 resolved / task_detail / Context 4；初始工作区干净，分支 feat/d9ce411f-2lhv；HEAD 与 origin/main 均为 4a5ed7f7f5ebb6d6f475995e3ad0904c5d8d295e；检索未发现本指标与 Ark model_request 埋点；源 MR merged=true，合并信息与需求一致。检索没有匹配时 rg 的退出码 1 表示零命中。

官方依赖调研（使用独立 /tmp 目录）：

```bash
git clone --depth 1 --branch v1.11.0 https://github.com/google/adk-java.git /tmp/veadk-tpot-adk-java
curl -fsSL https://maven.aliyun.com/repository/public/com/volcengine/volcengine-java-sdk-ark-runtime/0.2.48/volcengine-java-sdk-ark-runtime-0.2.48-sources.jar -o /tmp/veadk-tpot-ark-sources.jar
mkdir -p /tmp/veadk-tpot-ark-sources
(cd /tmp/veadk-tpot-ark-sources && jar xf /tmp/veadk-tpot-ark-sources.jar)
git clone --depth 1 https://github.com/langchain4j/langchain4j.git /tmp/veadk-tpot-langchain4j
rg -n 'streamChatCompletion|Flowable.create' /tmp/veadk-tpot-ark-sources/com/volcengine/ark/runtime/service/ArkService.java
rg -n 'onComplete|close|isCancelled' /tmp/veadk-tpot-ark-sources/com/volcengine/ark/runtime/utils/ResponseBodyCallback.java
rg -n 'reasoning|completion_tokens|JsonIgnoreProperties' /tmp/veadk-tpot-ark-sources/com/volcengine/ark/runtime/model/Usage.java /tmp/veadk-tpot-ark-sources/com/volcengine/ark/runtime/model/CompletionTokensDetails.java /tmp/veadk-tpot-ark-sources/com/volcengine/ark/runtime/model/completion/chat/ChatMessage.java
```

关键实际源码：

```java
// 官方 Ark SDK 0.2.48 ArkService.stream：真实 API Call → SDK SSE callback
return Flowable.create(emitter -> apiCall.enqueue(new ResponseBodyCallback(emitter, emitDone)), BackpressureStrategy.BUFFER);
// ResponseBodyCallback 顺序；完成不是可见的 close 成功证据
emitter.onComplete();
// ... finally 中执行；IOException 被吞掉
try { reader.close(); } catch (IOException e) { /* do nothing */ }
// 官方 ADK Java 1.11.0 Tracing.apply：包括所有终结方式
return pipeline.doFinally(lifecycle::end);
```

结论：官方 SDK 有 chunk/usage/推理 DTO，OTel 有指标 Reader，ADK 有请求上下文及 Span 能力，复用这些能力；成功清理与未知字段范围仍要单独核对。未发现本契约 TPOT；同类框架的请求耗时/Token 指标不能替代首末有效增量计量。

### Java 构建与运行命令

原环境无 JDK/Maven，临时安装 JDK 17 与 Maven。Maven Central 探测返回 HTTP 429，改用 Aliyun public 镜像解析同版本依赖；没有修改仓库 POM 或版本。以下命令在仓库根目录运行（不要把 /tmp 的验证源码加入分支）：

```bash
mkdir -p /tmp/veadk-tpot-validation /tmp/veadk-tpot-probe
git archive origin/main | tar -x -C /tmp/veadk-tpot-validation
cat > /tmp/veadk-tpot-maven-settings.xml <<'XML'
<settings><mirrors><mirror><id>aliyun</id><mirrorOf>central</mirrorOf><url>https://maven.aliyun.com/repository/public</url></mirror></mirrors></settings>
XML
mvn -s /tmp/veadk-tpot-maven-settings.xml -f /tmp/veadk-tpot-validation/pom.xml -pl core -am -DskipTests package
mvn -q -s /tmp/veadk-tpot-maven-settings.xml -f /tmp/veadk-tpot-validation/core/pom.xml dependency:build-classpath -Dmdep.outputFile=/tmp/veadk-tpot-probe/classpath.txt
mvn -q -s /tmp/veadk-tpot-maven-settings.xml -f /tmp/veadk-tpot-validation/pom.xml -pl core -am -Dtest=ArkLlmMappingTest,ArkResponsesTest,OpenTelemetryTest -Dsurefire.failIfNoSpecifiedTests=false test
# 先将下方四份源码按标题保存到 /tmp/veadk-tpot-probe
python /tmp/veadk-tpot-probe/sse_fixture.py > /tmp/veadk-tpot-probe/fixture.log 2>&1 &
tpot_fixture_pid=$!
tpot_cp="$(cat /tmp/veadk-tpot-probe/classpath.txt):/tmp/veadk-tpot-validation/core/target/classes"
javac -cp "$tpot_cp" /tmp/veadk-tpot-probe/UserAgent.java /tmp/veadk-tpot-probe/Probe.java
for scenario in normal tail length missing_finish; do
  java -cp "$tpot_cp:/tmp/veadk-tpot-probe" Probe "http://127.0.0.1:18793/$scenario/api/v3/" "/tmp/veadk-tpot-probe/results-$scenario"
done
python /tmp/veadk-tpot-probe/verify.py
kill "$tpot_fixture_pid"
```

Java 构建实际结果：BUILD SUCCESS。基线回归 46 tests：ArkLlmMappingTest 19、ArkResponsesTest 26、OpenTelemetryTest 1；failures=0、errors=0、skipped=0。本节点只针对观测相关基线运行这些回归，没有宣称全仓测试或新功能验收全部通过。README 的标准构建是 ./mvnw clean -DskipTests package，本次为隔离副本 core -am 的等价 Maven 构建；example 未参与。

### 用户程序：UserAgent.java

下面的用户代码仅负责创建 Agent、调用流式 Runner 并保存最终响应；不含遥测断言或模拟 SDK。

```java
import com.volcengine.veadk.Agent;
import com.volcengine.veadk.Runner;
import com.volcengine.veadk.model.ArkLlm;
import com.volcengine.veadk.model.ArkLlmConfig;
import com.google.adk.agents.RunConfig;
import com.google.adk.agents.RunConfig.StreamingMode;
import java.nio.file.Files;
import java.nio.file.Path;

public class UserAgent {
  public static void run(String base, Path output) throws Exception {
    try (ArkLlm model = new ArkLlm(ArkLlmConfig.builder().modelName("tpot-probe-" + java.net.URI.create(base).getPath().split("/")[1])
        .apiKey("local-fixture-key").apiBase(base).build())) {
      Agent agent = Agent.builder().name("tpot_java_probe").model(model)
          .instruction("Return the model response.").build();
      String reply = new Runner(agent).run("probe-user", "probe-session", "hello",
          RunConfig.builder().autoCreateSession(true).streamingMode(StreamingMode.SSE).build());
      Files.writeString(output, reply);
    }
  }
}
```

### 观测程序：Probe.java

独立初始化可收集 OTel。probe.reader_check 只用于证明 Reader 有效，是临时测试指标，不属于产品。保存 Span/metric_names/error 文件，业务输出单独保存在 reply.txt。

```java
import com.google.adk.telemetry.Tracing;
import com.volcengine.veadk.utils.JSONUtil;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.export.CollectionRegistration;
import io.opentelemetry.sdk.metrics.export.MetricReader;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.nio.file.*;
import java.util.*;

public class Probe {
  static class CapturingSpans implements SpanExporter {
    final List<Map<String,Object>> rows = Collections.synchronizedList(new ArrayList<>());
    public CompletableResultCode export(Collection<SpanData> spans) {
      for (var span : spans) {
        Map<String,Object> attrs = new TreeMap<>();
        span.getAttributes().forEach((k,v)->attrs.put(k.getKey(),v));
        rows.add(Map.of("name",span.getName(),"attributes",attrs));
      }
      return CompletableResultCode.ofSuccess();
    }
    public CompletableResultCode flush(){return CompletableResultCode.ofSuccess();}
    public CompletableResultCode shutdown(){return CompletableResultCode.ofSuccess();}
  }
  static class Reader implements MetricReader {
    CollectionRegistration registration;
    public void register(CollectionRegistration r){registration=r;}
    public AggregationTemporality getAggregationTemporality(InstrumentType t){return AggregationTemporality.CUMULATIVE;}
    public CompletableResultCode forceFlush(){return CompletableResultCode.ofSuccess();}
    public CompletableResultCode shutdown(){return CompletableResultCode.ofSuccess();}
  }
  public static void main(String[] args) throws Exception {
    Path out = Path.of(args[1]); Files.createDirectories(out);
    var spans = new CapturingSpans(); var reader = new Reader();
    var traces = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spans)).build();
    var meters = SdkMeterProvider.builder().registerMetricReader(reader).build();
    var sdk = OpenTelemetrySdk.builder().setTracerProvider(traces).setMeterProvider(meters).buildAndRegisterGlobal();
    Tracing.setTracerForTesting(GlobalOpenTelemetry.getTracer("veadk"));
    // Verify that the reader is collecting actual instruments before the user call.
    GlobalOpenTelemetry.getMeter("probe").histogramBuilder("probe.reader_check").build().record(1.0);
    String error = "";
    try { UserAgent.run(args[0],out.resolve("reply.txt")); }
    catch(Exception e) { error=e.getClass().getName()+": "+e.getMessage(); }
    traces.forceFlush();
    List<String> names = reader.registration.collectAllMetrics().stream().map(m->m.getName()).toList();
    Files.writeString(out.resolve("observation.json"),JSONUtil.toJson(Map.of("spans",spans.rows,"metric_names",names,"error",error)));
    sdk.close();
  }
}
```

### 可控上游：sse_fixture.py

SDK/HTTP/JSON 解析均为真实实现，上游是本地 fixture。首末发送间隔为约 0.99 秒，正常尾部 0.2 秒，对照尾部 5 秒；发送时间用于控制实验，不能代替将来采集的 SDK 接收时间。

```python
import json, time, pathlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
class Handler(BaseHTTPRequestHandler):
    def log_message(self,*a): pass
    def do_POST(self):
        request=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        pathlib.Path('/tmp/veadk-tpot-probe/request.json').write_text(json.dumps({'path':self.path,'stream':request.get('stream'),'include_usage':request.get('stream_options',{}).get('include_usage')}))
        scenario=request['model'].removeprefix('tpot-probe-')
        self.send_response(200); self.send_header('Content-Type','text/event-stream'); self.send_header('Connection','close'); self.end_headers()
        def emit(delta=None,finish=None,usage=None):
            obj={'id':'probe','object':'chat.completion.chunk','created':1,'model':'tpot-probe','choices':[] if usage else [{'index':0,'delta':delta or {},'finish_reason':finish}]}
            if usage: obj['usage']=usage
            self.wfile.write(('data: '+json.dumps(obj)+'\n\n').encode()); self.wfile.flush()
        emit({'content':'hello '}); time.sleep(.99); emit({'content':'world'})
        if scenario!='missing_finish': emit(finish='length' if scenario=='length' else 'stop')
        time.sleep(.2 if scenario!='tail' else 5)
        emit(usage={'prompt_tokens':5,'completion_tokens':100,'total_tokens':105})
        self.wfile.write(b'data: [DONE]\n\n'); self.wfile.flush()
ThreadingHTTPServer(('127.0.0.1',18793),Handler).serve_forever()
```

### 独立文件校验：verify.py

本校验断言的是**基线缺口**，不是新功能通过；新增实现后应依 PRD 验收矩阵替换为合格场景 count=1、sum/属性一致的正向断言。

```python
import json, pathlib
key='gen_ai.chat_completions.streaming_time_per_output_token'
root=pathlib.Path('/tmp/veadk-tpot-probe')
for scenario in ['normal','tail','length','missing_finish']:
 p=root/f'results-{scenario}'
 d=json.loads((p/'observation.json').read_text())
 assert 'probe.reader_check' in d['metric_names'], 'reader inactive'
 assert key not in d['metric_names']
 assert all(key not in s['attributes'] for s in d['spans'])
 assert all(s['name']!='model_request' for s in d['spans'])
 if scenario=='missing_finish':
  assert 'Ark stream ended without a finish reason.' in d['error']
 else:
  assert d['error']==''
  assert (p/'reply.txt').read_text()=='hello world'
 print(json.dumps({'scenario':scenario,'reply':None if d['error'] else (p/'reply.txt').read_text(),
  'error':d['error'],'model_request_spans':0,'tpot_attribute_count':0,'tpot_histogram_present':False,
  'reader_check':True},ensure_ascii=False))
print('PASS: baseline gap confirmed through Agent/Runner + real Ark SDK + HTTP SSE + OTel')
```


测试 fixture 的修正：Ark SDK 使用绝对接口路径 `/api/v3/chat/completions`，会覆盖 baseUrl 路径；最初按 URL 路径选择场景导致所有请求落入普通分支。已改为通过测试 model 名称选择场景，并重新运行全部四项。该修正只影响测试，未改 VeADK 或 SDK。

### Python 源 MR 契约验证

在独立目录检出源合并提交，安装声明范围内依赖；未修改源实现或原 MR 测试。以下测试包含真实支持的 LiteLLM CustomStreamWrapper、模型 generate_content_async、受控上游、内存 Span/Histogram 与精确单调时钟。

```bash
git clone https://github.com/volcengine/veadk-python.git /tmp/veadk-tpot-python
git -C /tmp/veadk-tpot-python checkout --detach 1763e5a548a952449d87e0ccfc11c2ec8d78c8cc
/opt/python3.12/bin/python3.12 -m venv /tmp/veadk-tpot-pyenv312
/tmp/veadk-tpot-pyenv312/bin/pip install 'google-adk>=2.7.0,<2.12.0' 'litellm>=1.83.7,<=1.83.14' 'pydantic-settings==2.10.1' 'opentelemetry-sdk>=1.37,<1.43' 'volcengine-python-sdk>=5.0.36' omegaconf pytest pytest-asyncio
PYTHONPATH=/tmp/veadk-tpot-python /tmp/veadk-tpot-pyenv312/bin/python -m pytest -q /tmp/veadk-tpot-python/tests/test_model_tpot.py
```

环境修正与结果：系统 Python 缺 ensurepip，改为现有 /opt/python3.12。首轮缺 volcenginesdkarkruntime，出现 17 个 Ark 导入失败、55 passed；安装声明的 volcengine-python-sdk 后重跑，**72 passed, 36 warnings in 4.44s**。警告包含 LiteLLM 使用 Pydantic 旧字段访问的弃用提示；未降级任何依赖。真实云调用跳过，仅 SDK/契约层 E2E，不声称验证云推理服务。

源验证关键代码（来自原 MR tests/test_model_tpot.py，完整代码在固定提交可读）：

```python
with timing.ModelTiming(streaming=True, model="test") as call:
    call.observe("text")
    recorded.clock.now = 990_000_000
    call.observe("text")
    recorded.clock.now = 10_000_000_000  # 尾部 usage/清理时间
    call.observe(None)
    call.complete(100)
    assert not points(recorded)  # 完成锁定后，干净退出前不发出
call.__exit__(None, None, None)  # 重复结束不能重复发出
assert recorded.spans.get_finished_spans()[0].attributes[TPOT] == pytest.approx(0.01)
[point] = points(recorded)
assert point.count == 1 and point.sum == pytest.approx(0.01)
```

实际执行原文件的 72 个用例，覆盖正常、usage 非法、零间隔、多候选、SDK 包装/隐藏字段、关闭 Trace 采样仍采集指标、异常、取消、清理失败、重复终结、并发与重试。原 MR `test_litellm_real_sdk_contract_and_unchanged_chunks` 使用真实 LiteLLM wrapper，仅替换外部 I/O；`test_retry_preserves_response_and_separates_tpot_samples` 从 RetryingLiteLlm.generate_content_async 消费最终响应并确认两个尝试/一个样本。

### Java E2E 实际结果

```text
{"scenario": "normal", "reply": "hello world", "error": "", "model_request_spans": 0, "tpot_attribute_count": 0, "tpot_histogram_present": false, "reader_check": true}
{"scenario": "tail", "reply": "hello world", "error": "", "model_request_spans": 0, "tpot_attribute_count": 0, "tpot_histogram_present": false, "reader_check": true}
{"scenario": "length", "reply": "hello world", "error": "", "model_request_spans": 0, "tpot_attribute_count": 0, "tpot_histogram_present": false, "reader_check": true}
{"scenario": "missing_finish", "reply": null, "error": "java.lang.IllegalStateException: Ark stream ended without a finish reason.", "model_request_spans": 0, "tpot_attribute_count": 0, "tpot_histogram_present": false, "reader_check": true}
PASS: baseline gap confirmed through Agent/Runner + real Ark SDK + HTTP SSE + OTel
```

实际 call_llm Span 含 gen_ai.request.model=tpot-probe-normal、gen_ai.usage.input_tokens=5、gen_ai.usage.output_tokens=100；Reader 同时导出 ADK workflow/invocation 指标，证明观测管线已启用。普通/尾部 5 秒均返回 hello world；length 当前也返回 hello world，但将来必须省略 TPOT；缺少 finish reason 则传播原 IllegalStateException。四种场景均无 model_request/TPOT，确认当前核心观测缺口。

**验证结论：需要同步；Java 缺口已端到端确认。** 本次未覆盖 Java 新 TPOT 的精确值、采样关闭、清理失败、并发重试等正向验收，因为功能尚未实现；这些在 PRD 第 4 节作为开发/E2E 必须完成的验证，不能以此基线测试替代。
