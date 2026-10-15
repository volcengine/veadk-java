/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. */
package com.volcengine.veadk.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvalSetLoaderTest {
    @TempDir Path directory;

    @Test
    void loadsPythonCompatibleEvaluationSet() throws Exception {
        Path file = directory.resolve("simple.test.json");
        Files.writeString(
                file,
                """
                {
                  "eval_set_id":"simple","name":"simple","description":null,
                  "eval_cases":[{"eval_id":"price","conversation":[{
                    "invocation_id":"inv-1",
                    "user_content":{"parts":[{"text":"Price"}],"role":"user"},
                    "final_response":{"parts":[{"text":"100"}]},
                    "intermediate_data":{"tool_uses":[{"id":"call-1","args":{"q":"Price"},"name":"search"}]}
                  }],"session_input":{"app_name":"app","user_id":"user","state":{}}}]
                }
                """);

        EvalSet evalSet = new EvalSetLoader().load(file);

        assertThat(evalSet.evalSetId()).isEqualTo("simple");
        assertThat(evalSet.evalCases().get(0).conversation().get(0).userContent().text())
                .isEqualTo("Price");
        assertThat(
                        evalSet.evalCases()
                                .get(0)
                                .conversation()
                                .get(0)
                                .intermediateData()
                                .toolUses()
                                .get(0)
                                .name())
                .isEqualTo("search");
    }

    @Test
    void recursivelyLoadsOnlyTestJsonFilesAndRejectsEmptyDirectory() throws Exception {
        Path nested = Files.createDirectories(directory.resolve("nested"));
        Files.writeString(nested.resolve("case.test.json"), validJson("one"));
        Files.writeString(nested.resolve("ignored.json"), validJson("ignored"));
        assertThat(new EvalSetLoader().loadAll(directory))
                .extracting(EvalSet::evalSetId)
                .containsExactly("one");

        Path empty = Files.createDirectory(directory.resolve("empty"));
        assertThatThrownBy(() -> new EvalSetLoader().loadAll(empty))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No *.test.json");
    }

    private static String validJson(String id) {
        return "{\"eval_set_id\":\""
                + id
                + "\",\"eval_cases\":[{\"eval_id\":\"case\",\"conversation\":[{\"user_content\":{\"parts\":[{\"text\":\"hi\"}]},\"final_response\":{\"parts\":[{\"text\":\"hello\"}]}}]}]}";
    }
}
