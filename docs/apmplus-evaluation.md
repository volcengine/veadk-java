# APMPlus 与 Agent 评测

## APMPlus

`APMPlusExporter` 按照 veadk-python 的配置契约，通过 OTLP/gRPC 同时导出 trace 和 metric：

```java
import com.volcengine.veadk.trace.OpenTelemetry;
import com.volcengine.veadk.trace.exporter.APMPlusExporter;
import java.util.List;

OpenTelemetry.initOpenTelemetry(List.of(new APMPlusExporter()));
```

需要在进程退出前主动发送缓冲数据时，可保留 exporter 实例并调用 `forceFlush()`。

支持以下环境变量：

- `OBSERVABILITY_OPENTELEMETRY_APMPLUS_ENDPOINT`：默认 `http://apmplus-<region>.volces.com:4317`
- `OBSERVABILITY_OPENTELEMETRY_APMPLUS_API_KEY`：APMPlus AppKey
- `OBSERVABILITY_OPENTELEMETRY_APMPLUS_SERVICE_NAME`：默认 `veadk_tracing`
- `REGION`：火山引擎地域，默认 `cn-beijing`
- `CLOUD_PROVIDER=byteplus` 时使用 `BYTEPLUS_REGION`，默认 `ap-southeast-1`

未提供 AppKey 时，SDK 会使用 `VOLCENGINE_ACCESS_KEY`、`VOLCENGINE_SECRET_KEY` 和可选的 `VOLCENGINE_SESSION_TOKEN`，调用 APMPlus `GetAppKey` OpenAPI 自动获取。BytePlus 环境对应使用 `BYTEPLUS_*` 凭证，并兼容回退到 `VOLCENGINE_*`。

## 评测

评测集兼容 Google ADK / veadk-python JSON 格式。单文件可直接加载；传入目录时会递归加载 `*.test.json`：

```java
EvalSet evalSet = new EvalSetLoader().load(Path.of("tests/simple.test.json"));
ADKEvaluator evaluator = new ADKEvaluator(runner);
OfflineEvaluator.EvaluationReport report = evaluator.evaluate(evalSet);
```

默认每个 case 独立运行两次，保持 case 内多轮会话，指标与 veadk-python 默认值一致：

- 工具调用轨迹精确匹配（工具名称、参数和顺序），阈值 `1.0`
- 最终响应 Rouge-1 F1，阈值 `0.8`

阈值和运行次数可显式配置：

```java
var options = new OfflineEvaluator.EvaluationOptions(3, 1.0, 0.75);
var report = evaluator.evaluate(evalSet, options);
```

运行过的会话可以原子写入评测集文件；同一评测集会追加 case，并拒绝重复的 `eval_id`：

```java
Path file = new EvalSetRecorder().save(
        runner, "user-1", "session-1", "weather", "beijing-weather", Path.of("tests"));
```

本地确定性指标与需要 judge model 的模型评测是两类能力；当前实现不隐式调用额外模型，便于在 CI 中稳定复现。
