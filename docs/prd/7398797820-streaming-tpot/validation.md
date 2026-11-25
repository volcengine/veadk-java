# TPOT 调研与端到端验证记录

## 验证范围与环境

本记录是需求设计阶段的基线验证，证明当前 Java 缺口与 Python 源契约。没有为 Java 实现 TPOT，也没有把将来的验收矩阵记为已通过。

- 日期：2026-11-20；Linux x86_64；JDK 17.0.21.1；Apache Maven 3.6.3；Java main 4a5ed7f7f5ebb6d6f475995e3ad0904c5d8d295e。
- Java 声明依赖：google-adk 1.11.0、Ark SDK 0.2.48、OpenTelemetry 1.62.0；未修改或降级依赖。
- Python：源合并提交 1763e5a548a952449d87e0ccfc11c2ec8d78c8cc；Python 3.12；google-adk 2.11.0、LiteLLM 1.83.14、OpenTelemetry SDK 1.42.1、volcengine-python-sdk 5.0.56、pytest 9.2.0、pytest-asyncio 1.5.0。
- 独立用户程序 UserAgent.java；测试/观测 Probe.java；本地上游 sse_fixture.py；独立 verify.py 校验文件。临时源码只在 /tmp，Git 中仅保存此说明与代码块。
- Java 验证副本以 git archive origin/main 导出，所有构建输出在 /tmp/veadk-tpot-validation；用户程序通过真实 VeADK Agent/Runner 和官方 SDK 发起 HTTP 流式请求。
- 无真实云请求：受控上游用于固定增量、尾部延迟、usage 与 finish reason；任务没有提供指定云端模型端点。此验证不证明生产推理字段范围或清理可观测性；发布前真实服务补验见 PRD 第 4 节。
- 无 AgentKit 控制台交互；本地 Agent 名称 tpot_java_probe、user probe-user、session probe-session，不涉及控制台 agentId。

## 调研命令与证据

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

## Java 构建与运行命令

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

## 用户程序：UserAgent.java

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

## 观测程序：Probe.java

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

## 可控上游：sse_fixture.py

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

## 独立文件校验：verify.py

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

## Python 源 MR 契约验证

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

## Java E2E 实际结果

```text
{"scenario": "normal", "reply": "hello world", "error": "", "model_request_spans": 0, "tpot_attribute_count": 0, "tpot_histogram_present": false, "reader_check": true}
{"scenario": "tail", "reply": "hello world", "error": "", "model_request_spans": 0, "tpot_attribute_count": 0, "tpot_histogram_present": false, "reader_check": true}
{"scenario": "length", "reply": "hello world", "error": "", "model_request_spans": 0, "tpot_attribute_count": 0, "tpot_histogram_present": false, "reader_check": true}
{"scenario": "missing_finish", "reply": null, "error": "java.lang.IllegalStateException: Ark stream ended without a finish reason.", "model_request_spans": 0, "tpot_attribute_count": 0, "tpot_histogram_present": false, "reader_check": true}
PASS: baseline gap confirmed through Agent/Runner + real Ark SDK + HTTP SSE + OTel
```

实际 call_llm Span 含 gen_ai.request.model=tpot-probe-normal、gen_ai.usage.input_tokens=5、gen_ai.usage.output_tokens=100；Reader 同时导出 ADK workflow/invocation 指标，证明观测管线已启用。普通/尾部 5 秒均返回 hello world；length 当前也返回 hello world，但将来必须省略 TPOT；缺少 finish reason 则传播原 IllegalStateException。四种场景均无 model_request/TPOT，确认当前核心观测缺口。

**验证结论：需要同步；Java 缺口已端到端确认。** 本次未覆盖 Java 新 TPOT 的精确值、采样关闭、清理失败、并发重试等正向验收，因为功能尚未实现；这些在 PRD 第 4 节作为开发/E2E 必须完成的验证，不能以此基线测试替代。

## 本轮复验与节点交付核对

**2026-11-25 复验：Java 同步缺口仍存在，原产品契约无需调整。** 显式拉取 main 后仍为 4a5ed7f7f5ebb6d6f475995e3ad0904c5d8d295e；初始工作分支 c5d5c1e41ae3da01b4eecd31fa1234d909c6af7d 仅包含本需求文档。仓库集合 Context 5 / resolved；历史产物 manifest 为空，但 Git 与指定飞书目录存在同名文档，已复用。

- 本轮工具：Linux x86_64、临时 JDK 17.0.21.1、Maven 3.9.9。Maven Wrapper 的 Central 下载失败后，从 Aliyun 下载相同 3.9.9；临时 JDK 补入环境已提供的代理 CA 后正常校验证书。没有禁用 TLS 校验、修改产品依赖或把验证源码加入 Git。
- 本轮重新按上方代码块生成 /tmp/veadk-tpot-probe 四份程序；通过 git archive origin/main 构建独立副本。Java 依赖仍为 ADK 1.11.0 / Ark 0.2.48 / OTel 1.62.0；Python 仍为 ADK 2.11.0 / LiteLLM 1.83.14 / OTel 1.42.1 / volcengine-python-sdk 5.0.56。
- 再次读取 ADK v1.11.0 Tracing、Ark 0.2.48 官方 sources 和固定 LangChain4j commit：流完成不等于清理成功；partial 回调不等于一个 Token。原差异化要求成立。

本轮命令（工作区根目录；`tpot_tools` 指向临时下载并解压的工具目录，沿用上方 Maven settings、用户程序与 fixture）：

```bash
git -C repos/volcengine_veadk-java fetch origin refs/heads/main:refs/remotes/origin/main
git -C repos/volcengine_veadk-java rev-parse origin/main
tpot_tools="$PWD/scratch/tpot-research"
export JAVA_HOME="$tpot_tools/jdk-17.0.21.1+2"
"$JAVA_HOME/bin/keytool" -importcert -noprompt -trustcacerts -alias veadk-probe-env-ca-0 -file /usr/local/share/ca-certificates/aio-proxy-ca-custom.crt -keystore "$JAVA_HOME/lib/security/cacerts" -storepass changeit
"$tpot_tools/apache-maven-3.9.9/bin/mvn" -s /tmp/veadk-tpot-maven-settings.xml -f /tmp/veadk-tpot-validation/pom.xml -pl core -am -DskipTests package
"$tpot_tools/apache-maven-3.9.9/bin/mvn" -q -s /tmp/veadk-tpot-maven-settings.xml -f /tmp/veadk-tpot-validation/core/pom.xml dependency:build-classpath -Dmdep.outputFile=/tmp/veadk-tpot-probe/classpath.txt
"$tpot_tools/apache-maven-3.9.9/bin/mvn" -B -ntp -s /tmp/veadk-tpot-maven-settings.xml -f /tmp/veadk-tpot-validation/pom.xml -pl core -am -Dtest=ArkLlmMappingTest,ArkResponsesTest,OpenTelemetryTest -Dsurefire.failIfNoSpecifiedTests=false test
# 按上方运行命令启动 fixture，用本轮 JDK 的 javac/java 运行四个场景，再执行 verify.py
/opt/python3.12/bin/python3.12 /tmp/veadk-tpot-probe/verify_metadata.py
PYTHONPATH="$tpot_tools/veadk-python-1763e5a548a952449d87e0ccfc11c2ec8d78c8cc" "$tpot_tools/pyenv/bin/python" -m pytest -q "$tpot_tools/veadk-python-1763e5a548a952449d87e0ccfc11c2ec8d78c8cc/tests/test_model_tpot.py"
```

独立追加校验源码 `/tmp/veadk-tpot-probe/verify_metadata.py`：

```python
import json
from pathlib import Path
root = Path('/tmp/veadk-tpot-probe')
for scenario in ['normal', 'tail', 'length']:
    d = json.loads((root / f'results-{scenario}' / 'observation.json').read_text())
    calls = [s for s in d['spans'] if s['name'] == 'call_llm']
    assert len(calls) == 1
    assert calls[0]['attributes']['gen_ai.request.model'] == f'tpot-probe-{scenario}'
    assert calls[0]['attributes']['gen_ai.usage.output_tokens'] == 100
request = json.loads((root / 'request.json').read_text())
assert request['stream'] is True and request['include_usage'] is True
assert request['path'] == '/api/v3/chat/completions'
print('PASS: streamed request/include_usage and final output_tokens=100 for normal/tail/length')
```

| 本轮验证 | 实际结果 |
| --- | --- |
| Java main core 隔离构建 | BUILD SUCCESS；未跑 example 模块或全仓测试 |
| Java 相关回归 | 46 tests，Failures=0，Errors=0，Skipped=0 |
| 正常 / 尾部 5 秒 / length | 三项均返回 hello world；各一条 call_llm，output_tokens=100；model_request 与 TPOT 属性/Histogram 均缺失 |
| 缺终态 | 保留 IllegalStateException: Ark stream ended without a finish reason.；无 TPOT |
| 观测与请求校验 | Reader 采到 probe.reader_check 与 ADK 指标；stream=true、include_usage=true；verify.py 与 verify_metadata.py 均 PASS |
| Python 源 MR | 72 passed, 36 warnings in 6.89s；警告为依赖弃用提示，未改源实现/测试 |

**验证边界：** 这是主分支缺口复验；Java 新 TPOT 的精确值、采样关闭、并发重试、故障注入和成功清理验收仍须在实现后完成。真实云服务未调用：受控 SSE 用于可重复验证，任务未指定真实模型端点；不能以本地结果证明云端推理计数范围或清理可观测性。无 AgentKit 控制台交互，沿用本地 Agent/user/session 标识。
