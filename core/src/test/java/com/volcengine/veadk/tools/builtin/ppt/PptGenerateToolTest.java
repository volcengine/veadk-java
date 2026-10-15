package com.volcengine.veadk.tools.builtin.ppt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.adk.tools.ToolContext;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PptGenerateToolTest {

    @Test
    void parsesFlatDeckAndCreatesPptxAndPreviewArtifacts() {
        ToolContext context = mock(ToolContext.class);
        when(context.saveArtifact(anyString(), any(Part.class))).thenReturn(Completable.complete());
        PptGenerateTool tool = new PptGenerateTool();

        Map<String, Object> result =
                tool.runAsync(
                                Map.of(
                                        "title", "Quarterly Review",
                                        "subtitle", "For leadership",
                                        "theme", "dark",
                                        "filename", "../review",
                                        "deck_markdown",
                                                "## Results\n"
                                                        + "Strong quarter\n"
                                                        + "- Revenue grew\n"
                                                        + "- Costs fell\n"
                                                        + "Sources: https://example.com |"
                                                        + " https://example.org\n"
                                                        + "## Next\n"
                                                        + "Plan\n"
                                                        + "- Ship"),
                                context)
                        .blockingGet();

        assertEquals("created", result.get("status"));
        assertEquals("review.pptx", result.get("filename"));
        assertEquals(3, result.get("slide_count"));
        verify(context, times(2)).saveArtifact(anyString(), any(Part.class));
        ArgumentCaptor<Part> artifact = ArgumentCaptor.forClass(Part.class);
        verify(context, times(2)).saveArtifact(anyString(), artifact.capture());
        List<Part> parts = artifact.getAllValues();
        byte[] preview = parts.get(0).inlineData().orElseThrow().data().orElseThrow();
        byte[] deck = parts.get(1).inlineData().orElseThrow().data().orElseThrow();
        assertTrue(preview.length > 100);
        assertEquals((byte) 0x89, preview[0]);
        assertTrue(deck.length > 1_000);
        assertEquals('P', deck[0]);
        assertEquals('K', deck[1]);
    }

    @Test
    void rejectsMissingTitleDeckAndArtifactContext() {
        PptGenerateTool tool = new PptGenerateTool();

        assertEquals(
                "title is required",
                tool.runAsync(Map.of("title", " ", "deck_markdown", "## A"), null)
                        .blockingGet()
                        .get("error"));
        assertTrue(
                tool.runAsync(Map.of("title", "A", "deck_markdown", "plain"), null)
                        .blockingGet()
                        .containsKey("error"));
        assertEquals(
                "tool context with artifact service is required",
                tool.runAsync(Map.of("title", "A", "deck_markdown", "## Slide"), null)
                        .blockingGet()
                        .get("error"));
    }

    @Test
    void parserBoundsSlidesBulletsAndSources() {
        String bullets =
                java.util.stream.IntStream.range(0, 12)
                        .mapToObj(i -> "- B" + i)
                        .collect(java.util.stream.Collectors.joining("\n"));
        var slides =
                PptGenerateTool.parseSlides(
                        "## Slide\nSummary\n" + bullets + "\n来源：https://example.com");

        assertEquals(1, slides.size());
        assertEquals(7, slides.get(0).bullets().size());
        assertEquals(List.of("https://example.com"), slides.get(0).sources());
    }
}
