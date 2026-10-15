/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. Licensed under Apache 2.0. */
package com.volcengine.veadk.tools.builtin.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.adk.tools.ToolContext;
import com.google.genai.types.Part;
import com.volcengine.ark.runtime.model.content.generation.CreateContentGenerationTaskRequest;
import com.volcengine.ark.runtime.model.content.generation.CreateContentGenerationTaskResult;
import com.volcengine.ark.runtime.model.content.generation.GetContentGenerationTaskRequest;
import com.volcengine.ark.runtime.model.content.generation.GetContentGenerationTaskResponse;
import com.volcengine.ark.runtime.model.images.generation.GenerateImagesRequest;
import com.volcengine.ark.runtime.model.images.generation.ImagesResponse;
import com.volcengine.ark.runtime.service.ArkService;
import io.reactivex.rxjava3.core.Completable;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MediaToolsTest {
    private final ArkService ark = mock(ArkService.class);

    @Test
    void defaultConstructorsDeferCredentialResolution() {
        new ImageGenerationTool().close();
        new ImageEditTool().close();
        new VideoGenerationTool().close();
        new VideoTaskQueryTool().close();
    }

    @Test
    void imageGenerationMapsArgumentsAndReturnsUrls() {
        ImagesResponse.Image image = new ImagesResponse.Image();
        image.setUrl("https://example.test/generated.png");
        image.setSize("2048x2048");
        ImagesResponse response = new ImagesResponse();
        response.setModel("seedream");
        response.setData(List.of(image));
        when(ark.generateImages(any())).thenReturn(response);
        var tool =
                new ImageGenerationTool(
                        "image_generate", "generate", ark, "seedream", false, false);

        Map<String, Object> result =
                tool.runAsync(
                                Map.of("prompt", "a fox", "size", "2048x2048", "watermark", false),
                                null)
                        .blockingGet();

        assertThat(result).containsEntry("status", "success");
        assertThat((List<?>) result.get("images")).hasSize(1);
        var captor = ArgumentCaptor.forClass(GenerateImagesRequest.class);
        verify(ark).generateImages(captor.capture());
        assertThat(captor.getValue().getPrompt()).isEqualTo("a fox");
        assertThat(captor.getValue().getSize()).isEqualTo("2048x2048");
        assertThat(captor.getValue().getWatermark()).isFalse();
    }

    @Test
    void imageGenerationPersistsBase64ResultAsArtifact() {
        ImagesResponse.Image image = new ImagesResponse.Image();
        image.setB64Json("AQID");
        ImagesResponse response = new ImagesResponse();
        response.setData(List.of(image));
        when(ark.generateImages(any())).thenReturn(response);
        ToolContext context = mock(ToolContext.class);
        when(context.saveArtifact(anyString(), any())).thenReturn(Completable.complete());
        var tool =
                new ImageGenerationTool(
                        "image_generate", "generate", ark, "seedream", false, false);

        Map<String, Object> result =
                tool.runAsync(Map.of("prompt", "a fox"), context).blockingGet();

        var artifact = ArgumentCaptor.forClass(Part.class);
        verify(context).saveArtifact(anyString(), artifact.capture());
        assertThat(artifact.getValue().inlineData().orElseThrow().data().orElseThrow())
                .containsExactly(1, 2, 3);
        assertThat(((List<?>) result.get("images")).get(0).toString())
                .contains("image_generate_0.png");
        var request = ArgumentCaptor.forClass(GenerateImagesRequest.class);
        verify(ark).generateImages(request.capture());
        assertThat(request.getValue().getResponseFormat()).isEqualTo("b64_json");
    }

    @Test
    void imageEditRequiresAndForwardsSourceImage() {
        ImagesResponse.Image image = new ImagesResponse.Image();
        image.setUrl("https://example.test/edited.png");
        ImagesResponse response = new ImagesResponse();
        response.setData(List.of(image));
        when(ark.generateImages(any())).thenReturn(response);
        var tool = new ImageGenerationTool("image_edit", "edit", ark, "seededit", false, true);

        assertThat(tool.runAsync(Map.of("prompt", "add rain"), null).blockingGet())
                .containsKey("error");
        tool.runAsync(
                        Map.of("prompt", "add rain", "image", "https://example.test/source.png"),
                        null)
                .blockingGet();
        var captor = ArgumentCaptor.forClass(GenerateImagesRequest.class);
        verify(ark).generateImages(captor.capture());
        assertThat(captor.getValue().getImage()).containsExactly("https://example.test/source.png");
    }

    @Test
    void videoSubmissionPreservesFrameRoles() {
        when(ark.createContentGenerationTask(any()))
                .thenReturn(new CreateContentGenerationTaskResult("task-1"));
        var tool = new VideoGenerationTool(ark, "seedance", false);
        Map<String, Object> result =
                tool.runAsync(
                                Map.of(
                                        "prompt",
                                        "camera pans",
                                        "first_frame",
                                        "https://example.test/start.png",
                                        "reference_images",
                                        List.of("https://example.test/ref.png")),
                                null)
                        .blockingGet();
        assertThat(result).containsEntry("task_id", "task-1").containsEntry("status", "submitted");
        var captor = ArgumentCaptor.forClass(CreateContentGenerationTaskRequest.class);
        verify(ark).createContentGenerationTask(captor.capture());
        assertThat(captor.getValue().getContent())
                .extracting(CreateContentGenerationTaskRequest.Content::getRole)
                .containsExactly(null, "first_frame", "reference_image");
    }

    @Test
    void videoQueryReturnsCompletedUrlAndFailureDetails() {
        GetContentGenerationTaskResponse.Content content =
                new GetContentGenerationTaskResponse.Content();
        content.setVideoUrl("https://example.test/video.mp4");
        GetContentGenerationTaskResponse response = new GetContentGenerationTaskResponse();
        response.setStatus("succeeded");
        response.setModel("seedance");
        response.setContent(content);
        when(ark.getContentGenerationTask(any())).thenReturn(response);
        Map<String, Object> result =
                new VideoTaskQueryTool(ark, false)
                        .runAsync(Map.of("task_id", "task-1"), null)
                        .blockingGet();
        assertThat(result)
                .containsEntry("status", "succeeded")
                .containsEntry("video_url", "https://example.test/video.mp4");
        var captor = ArgumentCaptor.forClass(GetContentGenerationTaskRequest.class);
        verify(ark).getContentGenerationTask(captor.capture());
        assertThat(captor.getValue().getTaskId()).isEqualTo("task-1");
    }
}
