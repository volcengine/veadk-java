# 多模态与文件

Java SDK 以 `veadk-python` 为基线，支持 Ark Responses 混合图文输入、Ark Files 附件上传、可重启恢复的本地 ADK Artifact，以及图片生成、图片编辑和异步视频生成。

## 模型输入与附件

`Part.fromBytes(bytes, "image/png")` 会转换成 data URL 形式的 `input_image`；`Part.fromUri(url, "image/jpeg")` 会转换成 URL 形式的 `input_image`。视频对应 `input_video`，其他文件对应 `input_file`。

本地附件先上传到 Ark Files，返回的 Part 可放入当前请求，也可随 ADK 会话历史在后续轮次继续引用：

```java
try (var files = new ArkFileService()) {
    Part attachment = files.upload(Path.of("report.pdf"), "application/pdf");
    Content prompt = Content.builder()
            .role("user")
            .parts(Part.fromText("总结这份报告"), attachment)
            .build();
    String answer = runner.run("user", "session", prompt, runConfig);
}
```

Files 默认限制 20 MiB，支持 PDF、PPTX、Markdown、纯文本、GIF/JPEG/PNG/WebP 以及 MP4/QuickTime/WebM。凭证使用 `MODEL_AGENT_API_KEY`，端点可用 `MODEL_AGENT_API_BASE` 覆盖。

## Artifact 持久化

默认 Runner 仍使用内存 Artifact。需要进程重启后继续加载生成文件时，显式注入 `LocalArtifactService`：

```java
var artifacts = new LocalArtifactService(Path.of("/data/veadk/artifacts"));
var runner = new Runner(agent, "my_app", artifacts);
```

存储按 app/user/session/filename 隔离，保留版本并原子写入。图片工具收到 `b64_json` 时会通过当前 `ToolContext` 保存 PNG Artifact；后续轮次可用 ADK `loadArtifact` 读取。

## 生成工具

```java
var imageGenerate = new ImageGenerationTool(); // image_generate
var imageEdit = new ImageEditTool();           // image_edit
var videoGenerate = new VideoGenerationTool(); // video_generate
var videoQuery = new VideoTaskQueryTool();      // video_task_query
```

图片工具分别使用 `MODEL_IMAGE_*` 和 `MODEL_EDIT_*`；视频工具使用 `MODEL_VIDEO_*`。各自的 API key 未配置时回退到 `MODEL_AGENT_API_KEY`，API base 未配置时回退到 `MODEL_AGENT_API_BASE`。凭证在工具真正执行时才解析。

视频生成是异步流程：先调用 `video_generate` 取得 `task_id`，再调用 `video_task_query`，状态为 `succeeded` 时返回 `video_url`。

## 真实调用示例

```bash
./mvnw -B -ntp install -DskipTests

# 混合图文，参数也可换成本地 PDF 路径
./mvnw -B -ntp -pl example exec:java \
  -Dexec.mainClass=com.volcengine.veadk.example.basic.MultimodalFilesExample \
  -Dexec.args='https://example.com/image.jpg'

# 图片生成 / 编辑
./mvnw -B -ntp -pl example exec:java \
  -Dexec.mainClass=com.volcengine.veadk.example.basic.MediaGenerationExample \
  -Dexec.args='image "a fox in snow"'
./mvnw -B -ntp -pl example exec:java \
  -Dexec.mainClass=com.volcengine.veadk.example.basic.MediaGenerationExample \
  -Dexec.args='edit https://example.com/source.png "add rain"'

# 视频提交 / 查询
./mvnw -B -ntp -pl example exec:java \
  -Dexec.mainClass=com.volcengine.veadk.example.basic.MediaGenerationExample \
  -Dexec.args='video "camera pans across a lake"'
./mvnw -B -ntp -pl example exec:java \
  -Dexec.mainClass=com.volcengine.veadk.example.basic.MediaGenerationExample \
  -Dexec.args='query cgt-xxxxxxxx'
```

这些命令会产生真实 Ark 调用及相应费用。
