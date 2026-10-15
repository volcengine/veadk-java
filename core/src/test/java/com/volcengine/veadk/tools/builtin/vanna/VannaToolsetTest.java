package com.volcengine.veadk.tools.builtin.vanna;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.adk.tools.BaseTool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VannaToolsetTest {

    @TempDir Path tempDirectory;

    @Test
    void runsReadOnlySqlThenSummarizesAndVisualizesCsv() throws Exception {
        Path database = tempDirectory.resolve("data.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                var statement = connection.createStatement()) {
            statement.execute("create table sales(name text, amount real)");
            statement.execute("insert into sales values ('A', 10.5), ('B', 20.0)");
        }
        VannaToolset toolset =
                new VannaToolset("sqlite://" + database, tempDirectory.resolve("files"));
        Map<String, BaseTool> tools =
                toolset.getTools(null).toList().blockingGet().stream()
                        .collect(Collectors.toMap(BaseTool::name, Function.identity()));

        Map<String, Object> query =
                tools.get("run_sql")
                        .runAsync(
                                Map.of("sql", "select name, amount from sales order by name"), null)
                        .blockingGet();
        assertEquals("success", query.get("status"));
        assertEquals(2, query.get("rows"));
        String filename = String.valueOf(query.get("filename"));
        assertTrue(Files.isRegularFile(tempDirectory.resolve("files").resolve(filename)));

        Map<String, Object> summary =
                tools.get("summarize_data")
                        .runAsync(Map.of("filename", filename), null)
                        .blockingGet();
        assertEquals(2, summary.get("rows"));
        @SuppressWarnings("unchecked")
        Map<String, Object> numeric = (Map<String, Object>) summary.get("numeric");
        assertTrue(numeric.containsKey("amount"));

        Map<String, Object> chart =
                tools.get("visualize_data")
                        .runAsync(Map.of("filename", filename, "title", "Sales"), null)
                        .blockingGet();
        assertEquals("success", chart.get("status"));
        assertTrue(
                Files.size(
                                tempDirectory
                                        .resolve("files")
                                        .resolve(String.valueOf(chart.get("filename"))))
                        > 100);
    }

    @Test
    void rejectsMutatingCteMultipleStatementsAndPathTraversal() throws Exception {
        Path database = tempDirectory.resolve("data.db");
        try (var ignored = DriverManager.getConnection("jdbc:sqlite:" + database)) {}
        VannaToolset toolset =
                new VannaToolset("sqlite://" + database, tempDirectory.resolve("files"));
        Map<String, BaseTool> tools =
                toolset.getTools(null).toList().blockingGet().stream()
                        .collect(Collectors.toMap(BaseTool::name, Function.identity()));

        for (String sql :
                List.of(
                        "delete from data",
                        "with x as (select 1) delete from data",
                        "select 1; drop table data")) {
            assertEquals(
                    "error",
                    tools.get("run_sql")
                            .runAsync(Map.of("sql", sql), null)
                            .blockingGet()
                            .get("status"));
        }
        assertEquals(
                "error",
                tools.get("summarize_data")
                        .runAsync(Map.of("filename", "../outside.csv"), null)
                        .blockingGet()
                        .get("status"));
    }

    @Test
    void parsesClickHouseCredentialsOutsideJdbcUrl() {
        VannaToolset.DatabaseConfig config =
                VannaToolset.DatabaseConfig.parse(
                        "clickhouse://analyst:p%40ss@db.example:8123/analytics?ssl=true");

        assertEquals("jdbc:clickhouse://db.example:8123/analytics?ssl=true", config.jdbcUrl());
        assertEquals("analyst", config.properties().getProperty("user"));
        assertEquals("p@ss", config.properties().getProperty("password"));
        assertFalse(config.jdbcUrl().contains("p%40ss"));
    }
}
