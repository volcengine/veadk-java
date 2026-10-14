/**
 * Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.volcengine.veadk.tools.builtin.createagent;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LoopAgent;
import com.google.adk.agents.ParallelAgent;
import com.google.adk.agents.ReadonlyContext;
import com.google.adk.agents.SequentialAgent;
import com.google.adk.events.EventActions;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.BaseToolset;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.volcengine.veadk.Agent;
import com.volcengine.veadk.RuntimeCompositeAgent;
import com.volcengine.veadk.utils.JSONUtil;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Discovers resources, creates runtime sub-agents, and transfers to the selected child. */
public final class CreateAgentToolset implements BaseToolset {

    private static final Pattern AGENT_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final int MAX_SNAPSHOTS = 128;

    private final List<ResourceSource> sources;
    private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<String, String> latestByOwner = new ConcurrentHashMap<>();
    private final Map<String, Agent> registrations = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> completedCreations = new ConcurrentHashMap<>();
    private final List<BaseTool> tools;

    public CreateAgentToolset() {
        this(List.of());
    }

    public CreateAgentToolset(List<? extends ResourceSource> sources) {
        this.sources = List.copyOf(Objects.requireNonNull(sources, "sources must be set."));
        this.tools = List.of(new CollectResourcesTool(), new CreateAgentsTool());
    }

    @Override
    public Flowable<BaseTool> getTools(ReadonlyContext readonlyContext) {
        return Flowable.fromIterable(tools);
    }

    @Override
    public void close() {
        registrations.forEach(
                (name, parent) -> {
                    BaseAgent child = parent.findAgent(name).orElse(null);
                    if (parent.unregisterRuntimeSubAgent(name)) {
                        if (child != null) {
                            child.close().blockingAwait();
                        }
                    }
                });
        registrations.clear();
        snapshots.clear();
        latestByOwner.clear();
        completedCreations.clear();
    }

    private Map<String, Object> collectResources(Map<String, Object> args, ToolContext context) {
        List<String> keywords =
                stringList(args.get("skill_hub_keywords")).stream()
                        .map(String::strip)
                        .filter(value -> !value.isEmpty())
                        .distinct()
                        .limit(5)
                        .toList();
        List<CompletableFuture<SourceResult>> futures =
                sources.stream()
                        .map(
                                source ->
                                        source.collect(keywords)
                                                .handle(
                                                        (resources, failure) ->
                                                                new SourceResult(
                                                                        source.name(),
                                                                        resources,
                                                                        failure)))
                        .toList();
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

        Map<String, ResourceDescriptor> resources = new LinkedHashMap<>();
        List<Map<String, Object>> statuses = new ArrayList<>();
        for (CompletableFuture<SourceResult> future : futures) {
            SourceResult result = future.join();
            if (result.failure != null) {
                statuses.add(
                        Map.of(
                                "source",
                                result.name,
                                "status",
                                "error",
                                "count",
                                0,
                                "message",
                                safeMessage(result.failure),
                                "search_keywords",
                                keywords));
                continue;
            }
            List<ResourceDescriptor> found =
                    result.resources == null ? List.of() : result.resources;
            found.forEach(resource -> resources.putIfAbsent(resource.ref(), resource));
            statuses.add(
                    Map.of(
                            "source",
                            result.name,
                            "status",
                            "ok",
                            "count",
                            found.size(),
                            "message",
                            "",
                            "search_keywords",
                            keywords));
        }

        String owner = owner(context);
        String previous = latestByOwner.get(owner);
        if (previous != null) {
            snapshots.remove(previous);
        }
        String collectionId =
                "resources_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        snapshots.put(collectionId, new Snapshot(owner, resources));
        latestByOwner.put(owner, collectionId);
        trimSnapshots();

        return Map.of(
                "collection_id",
                collectionId,
                "capabilities",
                Map.of(
                        "google_adk_version",
                        "1.11.0",
                        "agent_types",
                        List.of("llm", "sequential", "parallel", "loop")),
                "resources",
                resources.values().stream().map(ResourceDescriptor::publicView).toList(),
                "sources",
                statuses);
    }

    private Map<String, Object> createAgents(Map<String, Object> args, ToolContext context) {
        if (context == null) {
            throw new IllegalArgumentException(
                    "create_agents requires an active ADK agent invocation so created agents can be"
                            + " registered.");
        }
        if (!(context.invocationContext().agent() instanceof Agent parent)) {
            throw new IllegalArgumentException("create_agents requires a VeADK Agent parent.");
        }
        String creationKey = owner(context) + ":" + sha256(JSONUtil.toJson(args));
        Map<String, Object> cached = completedCreations.get(creationKey);
        if (cached != null) {
            String cachedHandoff = string(cached.get("handoff_to"));
            context.setActions(
                    context.actions().toBuilder().transferToAgent(cachedHandoff).build());
            return cached;
        }
        String collectionId = string(args.get("collection_id"));
        Snapshot snapshot;
        if (collectionId.isBlank()) {
            collectionId =
                    "resources_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            snapshot = new Snapshot(owner(context), Map.of());
        } else {
            snapshot = snapshots.get(collectionId);
            if (snapshot == null) {
                throw new IllegalArgumentException(
                        "Unknown or expired collection_id '"
                                + collectionId
                                + "'. Call collect_resources first.");
            }
            if (!snapshot.owner.equals(owner(context))) {
                throw new IllegalArgumentException(
                        "collection_id belongs to a different invocation or session. Call"
                                + " collect_resources in the current session.");
            }
        }

        List<Map<String, Object>> blueprints = mapList(args.get("agents"));
        if (blueprints.isEmpty()) {
            throw new IllegalArgumentException("agents must contain at least one blueprint.");
        }
        String handoffTo = requireName(string(args.get("handoff_to")), "handoff_to");
        Set<String> names = ConcurrentHashMap.newKeySet();
        List<Map<String, Object>> results = new ArrayList<>();
        List<BaseAgent> created = new ArrayList<>();
        String handoffRuntimeName = null;

        try {
            for (int index = 0; index < blueprints.size(); index++) {
                Map<String, Object> blueprint = blueprints.get(index);
                String name = requireName(string(blueprint.get("name")), "agent name");
                if (!names.add(name)) {
                    throw new IllegalArgumentException("Agent blueprint names must be unique.");
                }
                String task = requireText(string(blueprint.get("task")), "task");
                String runtimeName = runtimeName(name, collectionId, index);
                BuiltBlueprint built =
                        buildBlueprint(blueprint, runtimeName, task, snapshot, parent);
                BaseAgent child = built.root;
                parent.registerRuntimeSubAgent(child);
                registrations.put(runtimeName, parent);
                created.add(child);
                if (name.equals(handoffTo)) {
                    handoffRuntimeName = runtimeName;
                }
                results.add(
                        Map.of(
                                "name",
                                name,
                                "runtime_name",
                                runtimeName,
                                "description",
                                task,
                                "root_type",
                                built.rootType,
                                "status",
                                "completed",
                                "resources",
                                built.resources,
                                "python_tools",
                                List.of(),
                                "output",
                                ""));
            }
            if (handoffRuntimeName == null) {
                throw new IllegalArgumentException(
                        "handoff_to must name one of the supplied agent blueprints.");
            }
            EventActions actions =
                    context.actions().toBuilder().transferToAgent(handoffRuntimeName).build();
            context.setActions(actions);
            Map<String, Object> response =
                    Map.of(
                            "collection_id", collectionId,
                            "handoff_to", handoffRuntimeName,
                            "results", results);
            completedCreations.put(creationKey, response);
            while (completedCreations.size() > MAX_SNAPSHOTS) {
                completedCreations.remove(completedCreations.keySet().iterator().next());
            }
            return response;
        } catch (RuntimeException failure) {
            for (BaseAgent child : created) {
                parent.unregisterRuntimeSubAgent(child.name());
                registrations.remove(child.name());
                child.close().blockingAwait();
            }
            throw failure;
        }
    }

    private static BuiltBlueprint buildBlueprint(
            Map<String, Object> blueprint,
            String runtimeName,
            String task,
            Snapshot snapshot,
            Agent parent) {
        List<Map<String, Object>> nodes = mapList(blueprint.get("nodes"));
        if (nodes.isEmpty()) {
            nodes =
                    List.of(
                            Map.of(
                                    "id",
                                    "root",
                                    "type",
                                    "llm",
                                    "instruction",
                                    string(blueprint.get("instruction")),
                                    "resources",
                                    stringList(blueprint.get("resources"))));
        }
        String rootId =
                string(blueprint.get("root_node")).isBlank()
                        ? "root"
                        : requireName(string(blueprint.get("root_node")), "root_node");
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> node : nodes) {
            String id = requireName(string(node.get("id")), "node id");
            if (byId.putIfAbsent(id, node) != null) {
                throw new IllegalArgumentException(
                        "Node ids must be unique within one agent blueprint.");
            }
        }
        if (!byId.containsKey(rootId)) {
            throw new IllegalArgumentException("Root node '" + rootId + "' is not defined.");
        }
        Map<String, BaseAgent> built = new LinkedHashMap<>();
        Set<String> building = ConcurrentHashMap.newKeySet();
        Map<String, Map<String, Object>> selectedResources = new LinkedHashMap<>();
        BaseAgent root =
                buildNode(
                        rootId,
                        rootId,
                        byId,
                        built,
                        building,
                        runtimeName,
                        task,
                        snapshot,
                        parent,
                        selectedResources);
        if (built.size() != byId.size()) {
            Set<String> orphaned = ConcurrentHashMap.newKeySet();
            orphaned.addAll(byId.keySet());
            orphaned.removeAll(built.keySet());
            root.close().blockingAwait();
            throw new IllegalArgumentException(
                    "Blueprint contains unreachable nodes: " + orphaned + ".");
        }
        String rootType = string(byId.get(rootId).get("type"));
        BaseAgent transferRoot =
                root instanceof Agent ? root : new RuntimeCompositeAgent(runtimeName, task, root);
        return new BuiltBlueprint(transferRoot, rootType, List.copyOf(selectedResources.values()));
    }

    private static BaseAgent buildNode(
            String nodeId,
            String rootId,
            Map<String, Map<String, Object>> nodes,
            Map<String, BaseAgent> built,
            Set<String> building,
            String runtimeName,
            String task,
            Snapshot snapshot,
            Agent parent,
            Map<String, Map<String, Object>> selectedResources) {
        BaseAgent existing = built.get(nodeId);
        if (existing != null) {
            return existing;
        }
        Map<String, Object> node = nodes.get(nodeId);
        if (node == null) {
            throw new IllegalArgumentException("Node references undefined child '" + nodeId + "'.");
        }
        if (!building.add(nodeId)) {
            throw new IllegalArgumentException(
                    "Cyclic agent nesting detected at '" + nodeId + "'.");
        }
        String type = requireText(string(node.get("type")), "node type").toLowerCase();
        boolean root = nodeId.equals(rootId);
        String nodeName =
                root && "llm".equals(type)
                        ? runtimeName
                        : runtimeName + "_" + (root ? "flow" : nodeId);
        BaseAgent value;
        if ("llm".equals(type)) {
            List<Object> mounted = new ArrayList<>();
            for (String ref : stringList(node.get("resources"))) {
                ResourceDescriptor resource = snapshot.resources.get(ref);
                if (resource == null) {
                    throw new IllegalArgumentException(
                            "Unknown resource ref '" + ref + "' for node '" + nodeId + "'.");
                }
                mounted.add(resource.resource());
                selectedResources.putIfAbsent(ref, resource.publicView());
            }
            String instruction = string(node.get("instruction")).stripTrailing();
            value =
                    Agent.builder()
                            .name(nodeName)
                            .description(string(node.get("description")))
                            .instruction(
                                    instruction
                                            + "\n\n"
                                            + "Current delegated task (runtime context, not"
                                            + " reusable identity):\n"
                                            + task.strip())
                            .model(dynamicModel(parent, string(node.get("model"))))
                            .tools(mounted)
                            .build();
        } else {
            List<String> childIds = stringList(node.get("children"));
            if (childIds.isEmpty()) {
                throw new IllegalArgumentException(
                        "Orchestrator node '" + nodeId + "' must contain children.");
            }
            List<BaseAgent> children =
                    childIds.stream()
                            .map(
                                    childId ->
                                            buildNode(
                                                    childId,
                                                    rootId,
                                                    nodes,
                                                    built,
                                                    building,
                                                    runtimeName,
                                                    task,
                                                    snapshot,
                                                    parent,
                                                    selectedResources))
                            .toList();
            String description = string(node.get("description"));
            value =
                    switch (type) {
                        case "sequential" ->
                                SequentialAgent.builder()
                                        .name(nodeName)
                                        .description(description)
                                        .subAgents(children)
                                        .build();
                        case "parallel" ->
                                ParallelAgent.builder()
                                        .name(nodeName)
                                        .description(description)
                                        .subAgents(children)
                                        .build();
                        case "loop" ->
                                LoopAgent.builder()
                                        .name(nodeName)
                                        .description(description)
                                        .subAgents(children)
                                        .maxIterations(integer(node.get("max_iterations"), 3))
                                        .build();
                        default ->
                                throw new IllegalArgumentException(
                                        "Unsupported dynamic agent node type: " + type);
                    };
        }
        building.remove(nodeId);
        built.put(nodeId, value);
        return value;
    }

    private void trimSnapshots() {
        while (snapshots.size() > MAX_SNAPSHOTS) {
            String expired = snapshots.keySet().iterator().next();
            Snapshot removed = snapshots.remove(expired);
            if (removed != null) {
                latestByOwner.remove(removed.owner, expired);
            }
        }
    }

    private static String owner(ToolContext context) {
        if (context == null) {
            return "local";
        }
        return String.join(
                ":",
                context.invocationContext().appName(),
                context.userId(),
                context.sessionId(),
                context.invocationId());
    }

    private static String runtimeName(String name, String collectionId, int index) {
        // ADK Java 1.11 rejects consecutive separators, while ADK Python accepts "__".
        return name + "_" + sha256(collectionId + ":" + index).substring(0, 10);
    }

    private static BaseLlm dynamicModel(Agent parent, String requestedModel) {
        BaseLlm parentModel =
                parent.model()
                        .flatMap(com.google.adk.models.Model::model)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Parent agent has no resolved model for the dynamic"
                                                        + " child."));
        if (requestedModel == null
                || requestedModel.isBlank()
                || requestedModel.equals(parent.veadkModelName())
                || requestedModel.equals(parentModel.model())) {
            return new BorrowedLlm(parentModel);
        }
        throw new IllegalArgumentException(
                "Dynamic model '"
                        + requestedModel
                        + "' is not available in the current Java runtime. Omit model to inherit"
                        + " the parent model.");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String safeMessage(Throwable failure) {
        Throwable cause = failure.getCause() == null ? failure : failure.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private static String requireName(String value, String field) {
        String resolved = requireText(value, field);
        if (!AGENT_NAME.matcher(resolved).matches()) {
            throw new IllegalArgumentException(field + " must be a valid ADK agent identifier.");
        }
        return resolved;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must be set.");
        }
        return value;
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int integer(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(CreateAgentToolset::string).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .toList();
    }

    private final class CollectResourcesTool extends BaseTool {
        private CollectResourcesTool() {
            super(
                    "collect_resources",
                    "Collect resources before creating dynamic sub-agents. Pass two to five concise"
                            + " Skill Hub search keywords when public skill discovery is relevant.",
                    false);
        }

        @Override
        public java.util.Optional<FunctionDeclaration> declaration() {
            return java.util.Optional.of(
                    FunctionDeclaration.builder()
                            .name(name())
                            .description(description())
                            .parameters(
                                    Schema.builder()
                                            .type("OBJECT")
                                            .properties(
                                                    ImmutableMap.of(
                                                            "skill_hub_keywords",
                                                            Schema.builder()
                                                                    .type("ARRAY")
                                                                    .items(
                                                                            Schema.builder()
                                                                                    .type("STRING")
                                                                                    .build())
                                                                    .build()))
                                            .build())
                            .build());
        }

        @Override
        public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext context) {
            return Single.fromCallable(() -> collectResources(args, context));
        }
    }

    private final class CreateAgentsTool extends BaseTool {
        private CreateAgentsTool() {
            super(
                    "create_agents",
                    "Create one or more sub-agents and transfer to handoff_to. Only use when the"
                            + " user explicitly asks to create or delegate to a new agent. Normally"
                            + " call collect_resources first and use only refs returned by it. Call"
                            + " create_agents exactly once per collection.",
                    false);
        }

        @Override
        public java.util.Optional<FunctionDeclaration> declaration() {
            Schema freeObject = Schema.builder().type("OBJECT").build();
            return java.util.Optional.of(
                    FunctionDeclaration.builder()
                            .name(name())
                            .description(description())
                            .parameters(
                                    Schema.builder()
                                            .type("OBJECT")
                                            .properties(
                                                    ImmutableMap.of(
                                                            "collection_id",
                                                                    Schema.builder()
                                                                            .type("STRING")
                                                                            .build(),
                                                            "agents",
                                                                    Schema.builder()
                                                                            .type("ARRAY")
                                                                            .items(freeObject)
                                                                            .build(),
                                                            "handoff_to",
                                                                    Schema.builder()
                                                                            .type("STRING")
                                                                            .build()))
                                            .required(
                                                    ImmutableList.of(
                                                            "collection_id",
                                                            "agents",
                                                            "handoff_to"))
                                            .build())
                            .build());
        }

        @Override
        public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext context) {
            return Single.fromCallable(() -> createAgents(args, context));
        }
    }

    private record Snapshot(String owner, Map<String, ResourceDescriptor> resources) {
        private Snapshot {
            resources = Map.copyOf(resources);
        }
    }

    private record SourceResult(
            String name, List<ResourceDescriptor> resources, Throwable failure) {}

    private record BuiltBlueprint(
            BaseAgent root, String rootType, List<Map<String, Object>> resources) {}

    /** Delegates inference without transferring ownership of the parent's model connection. */
    private static final class BorrowedLlm extends BaseLlm {
        private final BaseLlm delegate;

        private BorrowedLlm(BaseLlm delegate) {
            super(delegate.model());
            this.delegate = delegate;
        }

        @Override
        public Flowable<LlmResponse> generateContent(LlmRequest request, boolean stream) {
            return delegate.generateContent(request, stream);
        }

        @Override
        public BaseLlmConnection connect(LlmRequest request) {
            return delegate.connect(request);
        }
    }
}
