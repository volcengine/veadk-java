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
package com.volcengine.veadk.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.genai.types.GenerateContentConfig;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionRequest.ChatCompletionRequestResponseFormat;
import com.volcengine.ark.runtime.model.completion.chat.ResponseFormatJSONSchemaJSONSchemaParam;
import com.volcengine.ark.runtime.model.responses.common.ResponsesText;
import com.volcengine.ark.runtime.model.responses.common.ResponsesTextFormat;
import com.volcengine.veadk.utils.JSONUtil;
import java.util.Locale;

/** Shared JSON output configuration for Ark's two protocols. */
final class ArkStructuredOutput {
    private ArkStructuredOutput() {}

    static JsonNode schema(GenerateContentConfig config) {
        if (config.responseSchema().isPresent() && config.responseJsonSchema().isPresent()) {
            throw new IllegalArgumentException(
                    "Set only one of responseSchema and responseJsonSchema.");
        }
        Object schema =
                config.responseJsonSchema().orElseGet(() -> config.responseSchema().orElse(null));
        if (schema == null) return null;
        if (config.responseMimeType()
                .filter(mime -> !mime.equals("application/json"))
                .isPresent()) {
            throw new IllegalArgumentException("JSON schema output requires application/json.");
        }
        JsonNode node = JSONUtil.valueToTree(schema).deepCopy();
        if (!node.isObject())
            throw new IllegalArgumentException("Response schema must be an object.");
        normalize(node);
        return node;
    }

    static void normalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            JsonNode type = object.get("type");
            if (type != null && type.isTextual()) {
                object.put("type", type.asText().toLowerCase(Locale.ROOT));
            }
            for (String map :
                    java.util.List.of("properties", "patternProperties", "$defs", "definitions")) {
                JsonNode children = object.get(map);
                if (children != null && children.isObject())
                    children.elements().forEachRemaining(ArkStructuredOutput::normalize);
            }
            for (String child :
                    java.util.List.of(
                            "items",
                            "additionalProperties",
                            "not",
                            "if",
                            "then",
                            "else",
                            "contains")) {
                JsonNode schema = object.get(child);
                if (schema != null) normalize(schema);
            }
            for (String list : java.util.List.of("allOf", "anyOf", "oneOf", "prefixItems")) {
                JsonNode children = object.get(list);
                if (children != null && children.isArray())
                    children.forEach(ArkStructuredOutput::normalize);
            }
            boolean nullable = object.path("nullable").asBoolean(false);
            object.remove(java.util.List.of("nullable", "propertyOrdering"));
            if (nullable) {
                ObjectNode nonNull = object.deepCopy();
                object.removeAll();
                // Keep root reference targets and URI scope outside the new union.
                for (String key :
                        java.util.List.of("$defs", "definitions", "$id", "$schema", "$anchor")) {
                    JsonNode value = nonNull.remove(key);
                    if (value != null) object.set(key, value);
                }
                object.putArray("anyOf").add(nonNull).addObject().put("type", "null");
            }
        }
    }

    static ChatCompletionRequestResponseFormat chatFormat(GenerateContentConfig config) {
        JsonNode schema = schema(config);
        if (schema != null) {
            return new ChatCompletionRequestResponseFormat(
                    "json_schema",
                    new ResponseFormatJSONSchemaJSONSchemaParam(
                            "agent_response", null, schema, true));
        }
        return config.responseMimeType()
                .filter("application/json"::equals)
                .map(ignored -> new ChatCompletionRequestResponseFormat("json_object"))
                .orElse(null);
    }

    static ResponsesText responsesFormat(GenerateContentConfig config) {
        JsonNode schema = schema(config);
        ResponsesTextFormat format;
        if (schema != null) {
            format = new JsonSchemaFormat(schema);
        } else if (config.responseMimeType().filter("application/json"::equals).isPresent()) {
            format = new ResponsesTextFormat();
            format.setType("json_object");
        } else {
            return null;
        }
        ResponsesText text = new ResponsesText();
        text.setFormat(format);
        return text;
    }

    // Ark SDK 0.2.48 models only the format type. Extend its bean so the actual
    // SDK serializer also sends the schema, name and strict fields.
    static final class JsonSchemaFormat extends ResponsesTextFormat {
        private final JsonNode schema;

        JsonSchemaFormat(JsonNode schema) {
            setType("json_schema");
            this.schema = schema;
        }

        @JsonProperty("name")
        public String getName() {
            return "agent_response";
        }

        @JsonProperty("schema")
        public JsonNode getSchema() {
            return schema;
        }

        @JsonProperty("strict")
        public boolean isStrict() {
            return true;
        }
    }
}
