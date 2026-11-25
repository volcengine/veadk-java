/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. */
package com.volcengine.veadk.model;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionChunk;
import com.volcengine.ark.runtime.service.ArkService;
import com.volcengine.veadk.trace.StreamingTpotRecorder;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class ArkStreamingObservationTest {
    static final class Fixture {
        final StreamingTpotRecorder recorder = mock(StreamingTpotRecorder.class);
        final AtomicLong clock = new AtomicLong();
        final ArkStreamingObservation observation =
                new ArkStreamingObservation(recorder, clock::get);

        Fixture() {
            observation.attach();
        }

        void send(String json) {
            observation.raw(json);
            try {
                observation.observe(
                        ArkService.defaultObjectMapper()
                                .readValue(json, ChatCompletionChunk.class));
            } catch (Exception failure) {
                observation.invalidate();
            }
        }

        void outputs() {
            send(delta("{\"content\":\"a\"}", null));
            clock.set(990_000_000);
            send(delta("{\"content\":\"b\"}", null));
        }

        void normal() {
            outputs();
            send(delta("{}", "stop"));
            send(usage("100", ""));
        }

        void finish() {
            observation.complete();
            observation.closeSucceeded();
        }

        void omitted() {
            finish();
            verify(recorder, never()).finish(anyDouble());
        }
    }

    static String delta(String message, String reason) {
        return "{\"choices\":[{\"index\":0,\"delta\":"
                + message
                + ",\"finish_reason\":"
                + (reason == null ? "null" : "\"" + reason + "\"")
                + "}]}";
    }

    static String usage(String tokens, String details) {
        return "{\"choices\":[],\"usage\":{\"completion_tokens\":" + tokens + details + "}}";
    }

    @Test
    void recordsPointZeroOneOnceAfterBothCompletionAndCleanupExcludingTail() {
        Fixture f = new Fixture();
        f.normal();
        f.clock.set(5_990_000_000L);
        f.observation.complete();
        verify(f.recorder, never()).finish(anyDouble());
        f.observation.closeSucceeded();
        f.observation.complete();
        f.observation.closeSucceeded();
        verify(f.recorder).finish(0.01);
    }

    @Test
    void successfulCleanupCanPrecedeFullDownstreamConsumption() {
        Fixture f = new Fixture();
        f.normal();
        f.observation.closeSucceeded();
        verify(f.recorder, never()).finish(anyDouble());
        f.observation.complete();
        verify(f.recorder).finish(0.01);
    }

    @Test
    void zeroDurationIsValidAndFinalUsageOverwritesEarlierCumulativeUsage() {
        Fixture f = new Fixture();
        f.send(delta("{\"content\":\"a\"}", null));
        f.send(usage("50", ""));
        f.send(delta("{\"content\":\"b\"}", "stop"));
        f.send(usage("100", ""));
        f.finish();
        verify(f.recorder).finish(0.0);
    }

    @Test
    void reasoningAndToolArgumentsInOneChunkAdvanceTimeOnlyOnce() {
        Fixture f = new Fixture();
        String combined =
                "{\"content\":\"a\",\"reasoning_content\":\"r\","
                        + "\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\"}}]}";
        f.send(delta(combined, null));
        f.clock.set(990_000_000);
        f.send(
                delta(
                        "{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"}\"}}]}",
                        "tool_calls"));
        f.send(usage("100", ",\"completion_tokens_details\":{\"reasoning_tokens\":20}"));
        f.finish();
        verify(f.recorder).finish(0.01);
    }

    @Test
    void metadataDoesNotAdvanceTime() {
        Fixture f = new Fixture();
        f.outputs();
        f.clock.set(10_000_000_000L);
        f.send(
                delta(
                        "{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"id\":\"id\",\"function\":{\"name\":\"tool\"}}]}",
                        null));
        f.send(delta("{}", "function_call"));
        f.send(usage("100", ""));
        f.finish();
        verify(f.recorder).finish(0.01);
    }

    @TestFactory
    Stream<DynamicTest> invalidTokenTypesAndRangesAreOmitted() {
        return Stream.of("null", "\"100\"", "100.9", "true", "-1", "0", "1", "9223372036854775808")
                .map(
                        tokens ->
                                DynamicTest.dynamicTest(
                                        "completion_tokens=" + tokens,
                                        () -> {
                                            Fixture f = new Fixture();
                                            f.outputs();
                                            f.send(delta("{}", "stop"));
                                            f.send(usage(tokens, ""));
                                            f.omitted();
                                        }));
    }

    @TestFactory
    Stream<DynamicTest> reasoningRangesMustBeTrusted() {
        return Stream.of("null", "\"2\"", "2.1", "true", "-1", "101", "2")
                .map(
                        tokens ->
                                DynamicTest.dynamicTest(
                                        "hidden or invalid reasoning=" + tokens,
                                        () -> {
                                            Fixture f = new Fixture();
                                            f.outputs();
                                            f.send(delta("{}", "stop"));
                                            f.send(
                                                    usage(
                                                            "100",
                                                            ",\"completion_tokens_details\":{\"reasoning_tokens\":"
                                                                    + tokens
                                                                    + "}"));
                                            f.omitted();
                                        }));
    }

    private record Rejection(String name, Consumer<Fixture> action) {}

    @TestFactory
    Stream<DynamicTest> pureReasoningAndPureToolParameterStreamsAreMeasured() {
        return Stream.of("reasoning", "tool")
                .map(
                        kind ->
                                DynamicTest.dynamicTest(
                                        kind,
                                        () -> {
                                            Fixture f = new Fixture();
                                            String message =
                                                    kind.equals("reasoning")
                                                            ? "{\"reasoning_content\":\"r\"}"
                                                            : "{\"tool_calls\":[{\"index\":0,\"type\":\"function\",\"function\":{\"arguments\":\"{}\"}}]}";
                                            f.send(delta(message, null));
                                            f.clock.set(990_000_000);
                                            f.send(
                                                    delta(
                                                            message,
                                                            kind.equals("reasoning")
                                                                    ? "stop"
                                                                    : "tool_calls"));
                                            f.send(
                                                    usage(
                                                            "100",
                                                            kind.equals("reasoning")
                                                                    ? ",\"completion_tokens_details\":{\"reasoning_tokens\":100}"
                                                                    : ""));
                                            f.finish();
                                            verify(f.recorder).finish(0.01);
                                        }));
    }

    @TestFactory
    Stream<DynamicTest> unknownObjectRoleAndToolTypesAreOmitted() {
        return Stream.of(
                        "{\"object\":\"chat.completion\",\"choices\":[]}",
                        delta("{\"role\":\"tool\",\"content\":\"a\"}", null),
                        delta(
                                "{\"tool_calls\":[{\"type\":\"unknown\",\"function\":{\"arguments\":\"{}\"}}]}",
                                null))
                .map(
                        json ->
                                DynamicTest.dynamicTest(
                                        json,
                                        () -> {
                                            Fixture f = new Fixture();
                                            f.send(json);
                                            f.normal();
                                            f.omitted();
                                        }));
    }

    @Test
    void parallelObservationsAndVisibleRetriesNeverShareTimingState() {
        Fixture a = new Fixture();
        Fixture b = new Fixture();
        Fixture failed = new Fixture();
        failed.outputs();
        failed.observation.invalidate();
        java.util.concurrent.CompletableFuture.allOf(
                        java.util.concurrent.CompletableFuture.runAsync(
                                () -> {
                                    a.normal();
                                    a.finish();
                                }),
                        java.util.concurrent.CompletableFuture.runAsync(
                                () -> {
                                    b.send(delta("{\"content\":\"a\"}", null));
                                    b.clock.set(1_980_000_000);
                                    b.send(delta("{\"content\":\"b\"}", "stop"));
                                    b.send(usage("100", ""));
                                    b.finish();
                                }))
                .join();
        verify(a.recorder).finish(0.01);
        verify(b.recorder).finish(0.02);
        verify(failed.recorder, never()).finish(anyDouble());
    }

    @TestFactory
    Stream<DynamicTest> eachLifecycleAndOutputBoundaryIsOmitted() {
        List<Rejection> cases =
                List.of(
                        new Rejection(
                                "missing usage",
                                f -> {
                                    f.outputs();
                                    f.send(delta("{}", "stop"));
                                }),
                        new Rejection(
                                "missing terminal",
                                f -> {
                                    f.outputs();
                                    f.send(usage("100", ""));
                                }),
                        new Rejection(
                                "no output",
                                f -> {
                                    f.send(delta("{}", "stop"));
                                    f.send(usage("100", ""));
                                }),
                        new Rejection(
                                "one increment",
                                f -> {
                                    f.send(delta("{\"content\":\"a\"}", "stop"));
                                    f.send(usage("100", ""));
                                }),
                        new Rejection(
                                "length",
                                f -> {
                                    f.outputs();
                                    f.send(delta("{}", "length"));
                                }),
                        new Rejection(
                                "filter",
                                f -> {
                                    f.outputs();
                                    f.send(delta("{}", "content_filter"));
                                }),
                        new Rejection(
                                "unknown finish",
                                f -> {
                                    f.outputs();
                                    f.send(delta("{}", "other"));
                                }),
                        new Rejection(
                                "duplicate terminal",
                                f -> {
                                    f.normal();
                                    f.send(delta("{}", "stop"));
                                }),
                        new Rejection(
                                "output after terminal",
                                f -> {
                                    f.normal();
                                    f.send(delta("{\"content\":\"late\"}", null));
                                }),
                        new Rejection(
                                "cancel before terminal",
                                f -> {
                                    f.outputs();
                                    f.observation.invalidate();
                                }),
                        new Rejection(
                                "cancel after terminal",
                                f -> {
                                    f.normal();
                                    f.observation.invalidate();
                                }),
                        new Rejection(
                                "cleanup failure",
                                f -> {
                                    f.normal();
                                    f.observation.complete();
                                    f.observation.invalidate();
                                }),
                        new Rejection(
                                "time reversed",
                                f -> {
                                    f.outputs();
                                    f.clock.set(0);
                                    f.send(delta("{\"content\":\"c\"}", "stop"));
                                    f.send(usage("100", ""));
                                }),
                        new Rejection(
                                "audio",
                                f -> {
                                    f.normal();
                                    f.send(delta("{\"audio\":{}}", null));
                                }),
                        new Rejection(
                                "summary",
                                f -> {
                                    f.normal();
                                    f.send(delta("{\"reasoning_summary\":\"r\"}", null));
                                }),
                        new Rejection(
                                "unknown output",
                                f -> {
                                    f.normal();
                                    f.send(delta("{\"future_output\":\"r\"}", null));
                                }),
                        new Rejection(
                                "unknown output count",
                                f -> {
                                    f.outputs();
                                    f.send(
                                            usage(
                                                    "100",
                                                    ",\"completion_tokens_details\":{\"audio_tokens\":2}"));
                                }),
                        new Rejection(
                                "missing reasoning count",
                                f -> {
                                    f.send(delta("{\"reasoning_content\":\"r\"}", null));
                                    f.clock.set(990_000_000);
                                    f.send(delta("{\"content\":\"b\"}", "stop"));
                                    f.send(usage("100", ""));
                                }),
                        new Rejection(
                                "cache replay", f -> f.send("{\"choices\":[],\"cache_hit\":true}")),
                        new Rejection(
                                "multi candidate",
                                f -> f.send("{\"choices\":[{\"index\":0},{\"index\":1}]}")),
                        new Rejection(
                                "wrong candidate index",
                                f ->
                                        f.send(
                                                "{\"choices\":[{\"index\":1,\"delta\":{\"content\":\"a\"}}]}")),
                        new Rejection(
                                "multiple wrapper fields",
                                f ->
                                        f.send(
                                                "{\"choices\":[{\"index\":0,\"delta\":{},\"message\":{}}]}")),
                        new Rejection(
                                "missing count",
                                f -> {
                                    f.outputs();
                                    f.send("{\"choices\":[],\"usage\":{}}");
                                }),
                        new Rejection("malformed raw JSON", f -> f.observation.raw("{")),
                        new Rejection(
                                "unknown SDK wrapper",
                                f -> {
                                    f.normal();
                                    f.observation.attach();
                                }));
        return cases.stream()
                .map(
                        c ->
                                DynamicTest.dynamicTest(
                                        c.name,
                                        () -> {
                                            Fixture f = new Fixture();
                                            c.action.accept(f);
                                            f.omitted();
                                        }));
    }

    @Test
    void clockFailureAndUnknownCleanupNeverRecord() throws Exception {
        StreamingTpotRecorder recorder = mock(StreamingTpotRecorder.class);
        ArkStreamingObservation observation =
                new ArkStreamingObservation(
                        recorder,
                        () -> {
                            throw new IllegalStateException();
                        });
        observation.attach();
        observation.observe(
                ArkService.defaultObjectMapper()
                        .readValue(delta("{\"content\":\"a\"}", null), ChatCompletionChunk.class));
        observation.complete();
        observation.closeSucceeded();
        verify(recorder, never()).finish(anyDouble());
        Fixture f = new Fixture();
        f.normal();
        f.observation.complete();
        verify(f.recorder, never()).finish(anyDouble());
    }

    @Test
    void synthesizedStopAndMismatchedSdkUsageAreRejected() throws Exception {
        Fixture f = new Fixture();
        f.outputs();
        f.observation.observe(
                ArkService.defaultObjectMapper()
                        .readValue(delta("{}", "stop"), ChatCompletionChunk.class));
        f.send(usage("100", ""));
        f.omitted();
        Fixture g = new Fixture();
        g.normal();
        g.observation.observe(
                ArkService.defaultObjectMapper()
                        .readValue(usage("99", ""), ChatCompletionChunk.class));
        g.omitted();
    }
}
