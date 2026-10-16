package com.volcengine.veadk.a2a;

import com.google.adk.a2a.executor.AgentExecutorConfig;
import com.google.adk.agents.BaseAgent;
import com.google.adk.artifacts.BaseArtifactService;
import com.google.adk.memory.BaseMemoryService;
import com.google.adk.sessions.BaseSessionService;
import io.a2a.server.agentexecution.AgentExecutor;
import io.a2a.server.agentexecution.RequestContext;
import io.a2a.server.events.EventQueue;
import io.a2a.server.events.QueueManager;
import io.a2a.server.requesthandlers.DefaultRequestHandler;
import io.a2a.server.tasks.TaskStore;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Bridges ADK's asynchronous execution to the A2A server's producer lifecycle.
 * Uses stable context IDs for sessions and keeps the producer alive until its final event is queued.
 * Supplied agent and services remain owned by the caller.
 * ADK 1.11 caches its model flow: cancellation stops task delivery but cannot guarantee
 * cancellation of an already running model request. See docs/a2a-collaboration.md.
 */
public final class VeA2AExecutor implements AgentExecutor {
    private final com.google.adk.a2a.executor.AgentExecutor delegate;
    private final Duration timeout;
    private final ConcurrentHashMap<String, CompletableFuture<Void>> completions =
            new ConcurrentHashMap<>();

    public VeA2AExecutor(
            BaseAgent agent,
            String appName,
            BaseSessionService sessions,
            BaseArtifactService artifacts,
            BaseMemoryService memory,
            Duration timeout) {
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(sessions, "sessions");
        Objects.requireNonNull(artifacts, "artifacts");
        Objects.requireNonNull(memory, "memory");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (appName == null || appName.isBlank())
            throw new IllegalArgumentException("appName must be set");
        if (timeout.isNegative()
                || timeout.isZero()
                || timeout.compareTo(Duration.ofSeconds(Integer.MAX_VALUE - 1L)) > 0
                || timeout.toMillis() < 1) {
            throw new IllegalArgumentException(
                    "timeout must be between 1ms and 2147483646 seconds");
        }
        var config =
                AgentExecutorConfig.builder()
                        .beforeExecuteCallback(
                                context ->
                                        Single.fromCallable(
                                                () -> {
                                                    // ADK 1.11/1.12 look up this ID but create a
                                                    // random ID on a miss.
                                                    // Pre-create through the public callback so its
                                                    // own lookup finds the same session.
                                                    String contextId = context.getContextId();
                                                    if (contextId == null || contextId.isBlank())
                                                        throw new IllegalArgumentException(
                                                                "contextId must be set");
                                                    String userId = "A2A_USER_" + contextId;
                                                    synchronized (sessions) {
                                                        if (sessions.getSession(
                                                                                appName,
                                                                                userId,
                                                                                contextId,
                                                                                Optional.empty())
                                                                        .blockingGet()
                                                                == null) {
                                                            sessions.createSession(
                                                                            appName, userId,
                                                                            Map.of(), contextId)
                                                                    .blockingGet();
                                                        }
                                                    }
                                                    return false;
                                                }))
                        .afterExecuteCallback(
                                (context, event) ->
                                        Maybe.just(event)
                                                // doFinally runs after ADK's downstream doOnSuccess
                                                // has enqueued the final event.
                                                .doFinally(() -> complete(context.getTaskId())))
                        .build();
        delegate =
                new com.google.adk.a2a.executor.AgentExecutor.Builder()
                        .agent(agent)
                        .appName(appName)
                        .sessionService(sessions)
                        .artifactService(artifacts)
                        .memoryService(memory)
                        .agentExecutorConfig(config)
                        .build();
    }

    /** Creates a transport-independent handler with matching bounded blocking timeouts. */
    public DefaultRequestHandler requestHandler(
            TaskStore tasks, QueueManager queues, Executor executor) {
        return RequestHandlerTimeouts.create(
                this,
                Objects.requireNonNull(tasks),
                Objects.requireNonNull(queues),
                Objects.requireNonNull(executor),
                (int) ((timeout.toMillis() + 999) / 1000) + 1);
    }

    @Override
    public void execute(RequestContext context, EventQueue queue) {
        String taskId = Objects.requireNonNull(context.getTaskId(), "taskId");
        var completion = new CompletableFuture<Void>();
        if (completions.putIfAbsent(taskId, completion) != null) {
            throw new IllegalStateException("A2A task is already executing");
        }
        try {
            delegate.execute(context, queue);
            completion.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            cancel(context, queue);
        } catch (InterruptedException e) {
            try {
                cancel(context, queue);
            } finally {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("A2A execution interrupted");
        } catch (ExecutionException e) {
            throw new IllegalStateException("A2A execution completion failed", e.getCause());
        } finally {
            completions.remove(taskId, completion);
        }
    }

    @Override
    public void cancel(RequestContext context, EventQueue queue) {
        try {
            delegate.cancel(context, queue);
        } finally {
            complete(context.getTaskId());
        }
    }

    private void complete(String taskId) {
        var completion = completions.get(taskId);
        if (completion != null) completion.complete(null);
    }
}
