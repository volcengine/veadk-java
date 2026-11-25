# VeADK Java 流式 TPOT 跨语言同步判断

**结论：需要同步，目标仅为 volcengine/veadk-java 的现有 Ark Chat Completions 模型请求侧 TPOT 采集与上报。**

## 来源与决策范围

| 项目 | 内容 |
| --- | --- |
| 需求来源 | GitHub MR 同步；[Python #1223](https://github.com/volcengine/veadk-python/pull/1223) |
| 增量 | 模型请求 Span 新增 TPOT 属性；同名 Histogram 请求级单样本；首末有效增量单调计时；真实终态、最终 usage、完整消费/清理及保守省略契约 |
| 源基线 | 1763e5a548a952449d87e0ccfc11c2ec8d78c8cc；2026-11-18 09:25:19 UTC 合并；7 文件 +909/-25 |
| Java 基线 | 2026-11-25 显式 fetch main 后 origin/main=4a5ed7f7f5ebb6d6f475995e3ad0904c5d8d295e；Context 5 main；工作分支 feat/d9ce411f-2lhv；初始 HEAD=c5d5c1e41ae3da01b4eecd31fa1234d909c6af7d 仅含本需求文档增量 |
| 关联工作项 | 7398797820；负责人/RD及共享指标契约协调人：单春华；PMO 丁彦俊；P3 |
| 去重 | 创建前去重证据沿用需求输入；当前任务为既有 Java 任务，未另建需求。历史 7390714084/7370916011 的上线信息不等同本次 Java 增量已实现 |

TPOT 直接支持模型流式性能评估，属于 VeADK 核心 Model / Observability 与火山 Ark 适配能力，应保持跨语言指标语义。Python Studio、Harness、CLI、MPA、Codex Runtime、packaging 和 typing-only 不纳入本次同步；本 MR 的 LiteLLM 内部兼容实现也不复制。

## 语言差异与同步要求

| 比较项 | Python 本次增量 | Java 当前证据及差异化要求 |
| --- | --- | --- |
| 请求计时位置 | TimedLiteLLMClient 观察 SDK chunk；Ark Responses observe_ark | ArkLlm concatMap 接收官方 SDK chunk，再缓冲文本；应在缓冲前观察，不能用 LlmResponse partial 时间 |
| 终态 | 检查 upstream received_finish_reason / response.completed，拒绝合成 stop | isStopChunk 接受任意非空 finishReason；TPOT 须独立确认正常原因，不能把 length/filter 算成功，不改变原有业务终态 |
| 分母 | 最后可信累计 completion_tokens（Chat）或 output_tokens（Responses） | 当前 usage 覆盖保存、最终映射；本次仅 completion_tokens，不加 reasoning_tokens，不累加 usage、不估算 |
| 推理/工具 | 识别文本/完整推理/工具增量；范围不符省略 | SDK reasoningContent、toolCalls 可复用；ADK 映射不暴露完整推理也不影响 SDK 侧可观测；完整性仍须官方字段及版本验证 |
| 生命周期 | complete 仅锁定资格，清理无错误后 __exit__ 单次发出 | RxJava 2→3；doFinally 涵盖取消/异常；SDK onComplete 先于 close 且 close 异常吞掉，必须有清理成功证据，无法确认则省略 |
| 指标 | 单次模型请求 Span 属性 + 同名 Histogram；采样独立 | OTel 已支持 MetricReader；没有 TPOT/Histogram，ArkLlm 未见 model_request；复用 ADK/OTel 补齐必要请求观测，不另建导出系统 |
| 依赖 | LiteLLM 内部契约测试保证允许版本范围 | 官方 ADK 1.11.0、Ark SDK 0.2.48、OTel 1.62.0；不得降级依赖，不要求 Java 引入 LiteLLM |

**共同契约：** `gen_ai.chat_completions.streaming_time_per_output_token`，double 秒，Histogram 单位 s；维度仅实际 gen_ai.request.model 和 gen_ai.operation.name=chat；TPOT=(首末有效 SDK 接收间隔)/(最终 completion_tokens-1)。首输出前及尾部 usage/清理/工具执行不计入；真实 0 秒可计，取消/异常/缺终态/清理失败及范围不可信不计。仅 model_request Span 持有属性，Histogram 不依赖 Trace 采样；sum/count 是请求平均，非 Token 加权。

Java main 实际已有 Responses 与 OpenAI compatible 适配；需求输入的“当前已有 Ark Chat”是本次范围边界，不应解释为 Java 全仓无 Responses。本节点不扩展这些 Provider。Go 不在本任务交付范围，本次未验证或输出 Go 实现结论。

## 官方与同类实现调研

- ADK Java v1.11.0（c8b1de6a1878ae2ac1c66d1e05fc30fc85b060b4）BaseLlm/Tracing 已提供 RxJava 3、per-subscription Span/context；没有此 TPOT 契约。复用原生层级，不复制 Python context manager。
- Ark SDK 0.2.48 官方 sources 发布包提供 choice index、finishReason、reasoningContent、completionTokensDetails；未知字段会丢弃、audio_tokens 未显式建模，不能把字段缺失当作计数范围已一致。
- LangChain4j 参考 commit c5f5d093fe14d7061570474bc6f5e3cb117ec155 的 listener 区分请求/完成/错误，Micrometer 统计请求耗时与 Token；partial 回调可能携带多 Token，因此不能以回调数替代 Token 分母。其请求总耗时也不能代替本 TPOT。

精确来源、字段证据、产品行为与验收见 [PRD](prd.md) 第 2、5 节。

## 端到端验证与差异结论

采用 Java 最新 main 的独立导出副本，不改业务源码；用户程序使用 VeADK Agent/Runner，官方 Ark SDK 向本地 SSE fixture 请求。观测程序单独初始化 OTel 并导出文件，另一个校验程序读取输出。Python 在源合并提交运行原 MR 72 项契约测试，全部通过；包括真实 LiteLLM SDK 包装和 RetryingLiteLlm.generate_content_async 行为，非真实云推理。

Java E2E 实际结果、完整代码和可执行命令见同需求目录 [validation.md](validation.md)；飞书版本在本判断文档附录包含关键命令与结果。没有真实云服务调用：当前节点验证 SDK/适配层缺口与契约，任务未指定真实 endpoint；可控服务便于检查尾部延迟和终态。云模型计数范围及清理成功仍需发布前补验，不能从本地 fixture 结果外推。无 AgentKit 控制台交互；本地 Agent=tpot_java_probe、user=probe-user、session=probe-session。

## 待确认与交付

RD 单春华与 Java 维护者在设计阶段核对完整推理/Token 计数范围、清理/取消可观测性，无法确认的流按既定契约省略；发布版本和 SDK 验证矩阵在发布前确定。这些问题不改变同步范围或产品省略规则。

交付为 Java 差异化 PRD 与同步判断/验证证据，业务实现由下游开发完成。[PRD](prd.md) REQ-001～004 和验收矩阵是开发依据；未增加其他语言工作项或发送通知。

## 附录：关键用户代码与对比验证命令

用户程序与测试程序分开，完整 fixture、Probe 和文件校验源码见验证记录；PRD 飞书附录包含完整代码。

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

```bash
# 先按 validation.md 准备源码与依赖，启动本地 SSE fixture
tpot_cp="$(cat /tmp/veadk-tpot-probe/classpath.txt):/tmp/veadk-tpot-validation/core/target/classes"
for scenario in normal tail length missing_finish; do
  java -cp "$tpot_cp:/tmp/veadk-tpot-probe" Probe "http://127.0.0.1:18793/$scenario/api/v3/" "/tmp/veadk-tpot-probe/results-$scenario"
done
python /tmp/veadk-tpot-probe/verify.py
PYTHONPATH=/tmp/veadk-tpot-python /tmp/veadk-tpot-pyenv312/bin/python -m pytest -q /tmp/veadk-tpot-python/tests/test_model_tpot.py
```

```text
{"scenario": "normal", "reply": "hello world", "error": "", "model_request_spans": 0, "tpot_attribute_count": 0, "tpot_histogram_present": false, "reader_check": true}
{"scenario": "tail", "reply": "hello world", "error": "", "model_request_spans": 0, "tpot_attribute_count": 0, "tpot_histogram_present": false, "reader_check": true}
{"scenario": "length", "reply": "hello world", "error": "", "model_request_spans": 0, "tpot_attribute_count": 0, "tpot_histogram_present": false, "reader_check": true}
{"scenario": "missing_finish", "reply": null, "error": "java.lang.IllegalStateException: Ark stream ended without a finish reason.", "model_request_spans": 0, "tpot_attribute_count": 0, "tpot_histogram_present": false, "reader_check": true}
PASS: baseline gap confirmed through Agent/Runner + real Ark SDK + HTTP SSE + OTel
Python: 72 passed, 36 warnings in 4.44s
Java baseline regression: 46 tests, 0 failures, 0 errors, 0 skipped
```

Java 现有 call_llm 输出 Token=100 且 ADK 指标已导出，仍缺本契约 TPOT；Python 原测试在同样 0.99 秒/100 Token 条件断言 TPOT=0.01、count=1；正常终态与尾部延迟判定差异由此可核验。Java 新增埋点的完整正向验收尚待开发，不把基线零采集结果记为目标功能验收通过。

## 本轮复验

2026-11-25 在同一 Java main 提交重新完成四项本地 E2E、46 项相关回归及源 Python 72 项契约测试；结果与前次一致。新增独立文件断言确认 stream/include_usage 为 true、正常/尾部/length 各一个 call_llm 且 output_tokens=100，Reader 可用而本契约 TPOT 缺失。完整可执行命令、追加校验代码与结果见 [验证记录](validation.md)“本轮复验与节点交付核对”，飞书 PRD 同节包含全文。

共享指标契约协调人为单春华；继续采用 REQ-003 的保守省略边界，清理成功证据与云端计数范围由下游设计和 E2E 核对，版本由维护者确定。未实施 Java 新埋点，未执行真实云请求。
