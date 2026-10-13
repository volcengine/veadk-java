package com.volcengine.veadk.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.models.LlmRequest;
import com.google.adk.plugins.ContextFilterPlugin;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import com.volcengine.veadk.Runner;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContextWindowTest {

    @Test
    void runnerKeepsToolCallAndResponseTogetherAtWindowBoundary() {
        ShortTermMemory memory = ShortTermMemory.builder().numRecentInvocationsToKeep(1).build();
        Runner runner = new Runner(new NoOpAgent(), "context_app", memory);
        ContextFilterPlugin plugin =
                (ContextFilterPlugin)
                        runner.pluginManager().getPlugin("veadk_recent_context").orElseThrow();
        LlmRequest.Builder request =
                LlmRequest.builder()
                        .contents(
                                List.of(
                                        text("user", "old question"),
                                        text("model", "old answer"),
                                        text("user", "use the tool"),
                                        functionCall("call-1"),
                                        functionResponse("call-1"),
                                        text("model", "tool answer"),
                                        text("user", "current question")));

        plugin.beforeModelCallback(null, request).blockingGet();

        List<Content> retained = request.build().contents();
        assertThat(retained).hasSize(5);
        assertThat(retained.get(0).text()).isEqualTo("use the tool");
        assertThat(retained.get(1).parts().orElseThrow().get(0).functionCall()).isPresent();
        assertThat(retained.get(2).parts().orElseThrow().get(0).functionResponse()).isPresent();
        assertThat(retained.get(4).text()).isEqualTo("current question");
    }

    private static Content text(String role, String text) {
        return Content.builder().role(role).parts(Part.fromText(text)).build();
    }

    private static Content functionCall(String id) {
        return Content.builder()
                .role("model")
                .parts(
                        Part.builder()
                                .functionCall(
                                        FunctionCall.builder()
                                                .id(id)
                                                .name("lookup")
                                                .args(Map.of("query", "value")))
                                .build())
                .build();
    }

    private static Content functionResponse(String id) {
        return Content.builder()
                .role("user")
                .parts(
                        Part.builder()
                                .functionResponse(
                                        FunctionResponse.builder()
                                                .id(id)
                                                .name("lookup")
                                                .response(Map.of("result", "value")))
                                .build())
                .build();
    }

    private static final class NoOpAgent extends BaseAgent {
        private NoOpAgent() {
            super("context_agent", "test agent", List.of(), List.of(), List.of());
        }

        @Override
        protected Flowable<Event> runAsyncImpl(InvocationContext invocationContext) {
            return Flowable.empty();
        }

        @Override
        protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
            return Flowable.empty();
        }
    }
}
