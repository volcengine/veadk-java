/**
 * Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. and/or its affiliates.
 * Licensed under the Apache License, Version 2.0.
 */
package com.volcengine.veadk.tools.builtin.vanna;

import com.google.adk.agents.ReadonlyContext;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.BaseToolset;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

/**
 * Safe Java counterpart of the Python Vanna data toolset.
 *
 * <p>It exposes a read-only SQL-to-CSV flow, CSV summaries, and a basic PNG visualization. Local
 * process execution and package installation are intentionally delegated to the existing sandbox
 * tools instead of running arbitrary code in the SDK process.
 */
public final class VannaToolset implements BaseToolset {

    private final List<BaseTool> tools;

    public VannaToolset(String connectionString, Path fileStorage) {
        this(DatabaseConfig.parse(connectionString), fileStorage);
    }

    VannaToolset(DatabaseConfig database, Path fileStorage) {
        Path storage = prepareStorage(fileStorage);
        this.tools =
                List.of(
                        new RunSqlTool(database, storage),
                        new SummarizeDataTool(storage),
                        new VisualizeDataTool(storage));
    }

    @Override
    public Flowable<BaseTool> getTools(ReadonlyContext readonlyContext) {
        return Flowable.fromIterable(tools);
    }

    @Override
    public void close() {}

    private static Path prepareStorage(Path path) {
        if (path == null) throw new IllegalArgumentException("fileStorage must be set");
        try {
            Files.createDirectories(path);
            return path.toRealPath();
        } catch (IOException failure) {
            throw new IllegalArgumentException("fileStorage cannot be created", failure);
        }
    }

    static final class RunSqlTool extends BaseTool {
        private static final Pattern READ_ONLY =
                Pattern.compile(
                        "^(select|with|show|describe|desc|explain)\\b", Pattern.CASE_INSENSITIVE);
        private static final Pattern MUTATING =
                Pattern.compile(
                        "\\b(insert|update|delete|drop|alter|create|truncate|grant|revoke|attach|detach|optimize|system|kill|rename|replace|merge|call|execute)\\b",
                        Pattern.CASE_INSENSITIVE);
        private final DatabaseConfig database;
        private final Path storage;

        private RunSqlTool(DatabaseConfig database, Path storage) {
            super("run_sql", "Execute a read-only SQL query and save the result as a CSV file.");
            this.database = database;
            this.storage = storage;
        }

        @Override
        public Optional<FunctionDeclaration> declaration() {
            return Optional.of(
                    VannaToolset.declaration(
                            name(), description(), Map.of("sql", stringSchema()), "sql"));
        }

        @Override
        public Single<Map<String, Object>> runAsync(
                Map<String, Object> args, ToolContext toolContext) {
            return Single.fromCallable(() -> run(string(args.get("sql"))));
        }

        private Map<String, Object> run(String rawSql) {
            String sql = rawSql.strip();
            if (sql.isEmpty()) return error("No SQL query provided");
            String inspected =
                    sql.replaceAll("'([^']|'')*'", "''")
                            .replaceAll("(?s)/\\*.*?\\*/|--[^\\r\\n]*", " ");
            if (!READ_ONLY.matcher(inspected).find()
                    || MUTATING.matcher(inspected).find()
                    || hasMultipleStatements(inspected)) {
                return error("Only one read-only SQL statement is allowed");
            }
            try (Connection connection = database.open()) {
                database.configureReadOnly(connection);
                try (Statement statement = connection.createStatement()) {
                    statement.setQueryTimeout(60);
                    statement.setMaxRows(10_000);
                    try (ResultSet resultSet = statement.executeQuery(sql)) {
                        CsvData data = CsvData.from(resultSet);
                        Path output = Files.createTempFile(storage, "query-", ".csv");
                        Files.writeString(output, data.csv(), StandardCharsets.UTF_8);
                        return Map.of(
                                "status", "success",
                                "filename", output.getFileName().toString(),
                                "rows", data.rowCount(),
                                "columns", data.headers(),
                                "preview", data.preview());
                    }
                }
            } catch (SQLException | IOException failure) {
                return error("SQL query failed: " + safeMessage(failure));
            }
        }

        private static boolean hasMultipleStatements(String sql) {
            String withoutTrailing = sql.replaceFirst(";\\s*$", "");
            return withoutTrailing.indexOf(';') >= 0;
        }
    }

    static final class SummarizeDataTool extends BaseTool {
        private final Path storage;

        private SummarizeDataTool(Path storage) {
            super("summarize_data", "Generate a structural and numeric summary of a CSV result.");
            this.storage = storage;
        }

        @Override
        public Optional<FunctionDeclaration> declaration() {
            return Optional.of(
                    VannaToolset.declaration(
                            name(), description(), Map.of("filename", stringSchema()), "filename"));
        }

        @Override
        public Single<Map<String, Object>> runAsync(
                Map<String, Object> args, ToolContext toolContext) {
            return Single.fromCallable(() -> summarize(string(args.get("filename"))));
        }

        private Map<String, Object> summarize(String filename) {
            try {
                List<List<String>> rows = readCsv(confinedFile(storage, filename));
                if (rows.isEmpty()) return error("CSV file is empty");
                List<String> headers = rows.get(0);
                LinkedHashMap<String, Object> numeric = new LinkedHashMap<>();
                for (int column = 0; column < headers.size(); column++) {
                    List<Double> values = new ArrayList<>();
                    for (int row = 1; row < rows.size(); row++) {
                        if (column >= rows.get(row).size()) continue;
                        try {
                            values.add(Double.parseDouble(rows.get(row).get(column)));
                        } catch (NumberFormatException ignored) {
                            // Non-numeric cells do not participate in numeric statistics.
                        }
                    }
                    if (!values.isEmpty()) {
                        double sum = values.stream().mapToDouble(Double::doubleValue).sum();
                        numeric.put(
                                headers.get(column),
                                Map.of(
                                        "count", values.size(),
                                        "min",
                                                values.stream()
                                                        .mapToDouble(Double::doubleValue)
                                                        .min()
                                                        .orElse(0),
                                        "max",
                                                values.stream()
                                                        .mapToDouble(Double::doubleValue)
                                                        .max()
                                                        .orElse(0),
                                        "mean", sum / values.size()));
                    }
                }
                return Map.of(
                        "status",
                        "success",
                        "rows",
                        Math.max(0, rows.size() - 1),
                        "columns",
                        headers,
                        "numeric",
                        numeric,
                        "preview",
                        rows.stream().limit(6).toList());
            } catch (IOException | IllegalArgumentException failure) {
                return error("CSV summary failed: " + safeMessage(failure));
            }
        }
    }

    static final class VisualizeDataTool extends BaseTool {
        private final Path storage;

        private VisualizeDataTool(Path storage) {
            super("visualize_data", "Create a PNG bar chart from the first numeric CSV column.");
            this.storage = storage;
        }

        @Override
        public Optional<FunctionDeclaration> declaration() {
            return Optional.of(
                    VannaToolset.declaration(
                            name(),
                            description(),
                            Map.of("filename", stringSchema(), "title", stringSchema()),
                            "filename"));
        }

        @Override
        public Single<Map<String, Object>> runAsync(
                Map<String, Object> args, ToolContext toolContext) {
            return Single.fromCallable(
                    () ->
                            visualize(
                                    string(args.get("filename")),
                                    string(args.get("title")),
                                    toolContext));
        }

        private Map<String, Object> visualize(
                String filename, String requestedTitle, ToolContext context) {
            try {
                List<List<String>> rows = readCsv(confinedFile(storage, filename));
                if (rows.size() < 2) return error("CSV file has no data rows");
                int numericColumn = firstNumericColumn(rows);
                if (numericColumn < 0) return error("CSV file has no numeric column");
                String title =
                        requestedTitle.isBlank()
                                ? rows.get(0).get(numericColumn)
                                : requestedTitle.strip();
                byte[] png = chart(rows, numericColumn, title);
                String outputName = filename.replaceFirst("(?i)\\.csv$", "") + ".chart.png";
                Path output = storage.resolve(outputName);
                Files.write(output, png);
                if (context != null) {
                    context.saveArtifact(outputName, Part.fromBytes(png, "image/png"))
                            .blockingAwait();
                }
                return Map.of(
                        "status",
                        "success",
                        "filename",
                        outputName,
                        "numeric_column",
                        rows.get(0).get(numericColumn));
            } catch (IOException | IllegalArgumentException failure) {
                return error("CSV visualization failed: " + safeMessage(failure));
            }
        }

        private static int firstNumericColumn(List<List<String>> rows) {
            for (int column = 0; column < rows.get(0).size(); column++) {
                boolean found = false;
                for (int row = 1; row < rows.size(); row++) {
                    if (column >= rows.get(row).size()) continue;
                    try {
                        Double.parseDouble(rows.get(row).get(column));
                        found = true;
                    } catch (NumberFormatException ignored) {
                        found = false;
                        break;
                    }
                }
                if (found) return column;
            }
            return -1;
        }

        private static byte[] chart(List<List<String>> rows, int column, String title)
                throws IOException {
            List<Double> values =
                    rows.stream()
                            .skip(1)
                            .limit(20)
                            .map(row -> Double.parseDouble(row.get(column)))
                            .toList();
            double max = values.stream().mapToDouble(Math::abs).max().orElse(1);
            if (max == 0) max = 1;
            BufferedImage image = new BufferedImage(1000, 600, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            try {
                graphics.setRenderingHint(
                        RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, 1000, 600);
                graphics.setColor(new Color(16, 35, 63));
                graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 28));
                graphics.drawString(title, 50, 55);
                int width = Math.max(8, 850 / values.size());
                for (int index = 0; index < values.size(); index++) {
                    int height = (int) (440 * Math.abs(values.get(index)) / max);
                    graphics.setColor(new Color(47, 111, 237));
                    graphics.fillRect(70 + index * width, 530 - height, width - 4, height);
                }
            } finally {
                graphics.dispose();
            }
            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                ImageIO.write(image, "png", output);
                return output.toByteArray();
            }
        }
    }

    record DatabaseConfig(String jdbcUrl, Properties properties) {
        static DatabaseConfig parse(String connectionString) {
            if (connectionString == null || connectionString.isBlank()) {
                throw new IllegalArgumentException("connectionString must be set");
            }
            String value = connectionString.strip();
            if (value.startsWith("jdbc:")) return new DatabaseConfig(value, new Properties());
            if (value.startsWith("sqlite://")) {
                Properties properties = new Properties();
                properties.setProperty("open_mode", "1");
                return new DatabaseConfig(
                        "jdbc:sqlite:" + value.substring("sqlite://".length()), properties);
            }
            if (value.startsWith("clickhouse://")
                    || value.startsWith("postgresql://")
                    || value.startsWith("mysql://")) {
                URI uri = URI.create(value);
                Properties properties = new Properties();
                if (uri.getRawUserInfo() != null) {
                    String[] parts = uri.getRawUserInfo().split(":", 2);
                    properties.setProperty("user", decode(parts[0]));
                    if (parts.length == 2) properties.setProperty("password", decode(parts[1]));
                }
                String scheme = uri.getScheme();
                String host = uri.getHost();
                if (host == null || host.isBlank()) {
                    throw new IllegalArgumentException("database host must be set");
                }
                String authority = host + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
                String suffix =
                        (uri.getRawPath() == null ? "" : uri.getRawPath())
                                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
                return new DatabaseConfig(
                        "jdbc:" + scheme + "://" + authority + suffix, properties);
            }
            throw new IllegalArgumentException(
                    "Supported connection strings: sqlite, postgresql, mysql, clickhouse, or jdbc");
        }

        private Connection open() throws SQLException {
            return DriverManager.getConnection(jdbcUrl, properties);
        }

        private void configureReadOnly(Connection connection) throws SQLException {
            // Xerial SQLite cannot change this flag after opening a connection. SQL validation
            // remains the enforcement layer for the local test/development backend.
            if (!jdbcUrl.startsWith("jdbc:sqlite:")) connection.setReadOnly(true);
        }

        private static String decode(String value) {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        }
    }

    private record CsvData(
            String csv, int rowCount, List<String> headers, List<List<String>> preview) {
        private static CsvData from(ResultSet resultSet) throws SQLException {
            ResultSetMetaData metadata = resultSet.getMetaData();
            List<String> headers = new ArrayList<>();
            for (int column = 1; column <= metadata.getColumnCount(); column++) {
                headers.add(metadata.getColumnLabel(column));
            }
            StringBuilder csv = new StringBuilder(csvRow(headers));
            List<List<String>> preview = new ArrayList<>();
            preview.add(headers);
            int count = 0;
            while (resultSet.next()) {
                List<String> row = new ArrayList<>();
                for (int column = 1; column <= metadata.getColumnCount(); column++) {
                    Object value = resultSet.getObject(column);
                    row.add(value == null ? "" : String.valueOf(value));
                }
                csv.append('\n').append(csvRow(row));
                if (preview.size() <= 20) preview.add(List.copyOf(row));
                count++;
            }
            return new CsvData(csv.toString(), count, List.copyOf(headers), List.copyOf(preview));
        }
    }

    private static String csvRow(List<String> values) {
        return values.stream()
                .map(VannaToolset::csvCell)
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static String csvCell(String value) {
        return value.contains(",") || value.contains("\"") || value.contains("\n")
                ? "\"" + value.replace("\"", "\"\"") + "\""
                : value;
    }

    private static List<List<String>> readCsv(Path path) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            List<String> row = new ArrayList<>();
            StringBuilder cell = new StringBuilder();
            boolean quoted = false;
            for (int index = 0; index < line.length(); index++) {
                char character = line.charAt(index);
                if (character == '"') {
                    if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                        cell.append('"');
                        index++;
                    } else {
                        quoted = !quoted;
                    }
                } else if (character == ',' && !quoted) {
                    row.add(cell.toString());
                    cell.setLength(0);
                } else {
                    cell.append(character);
                }
            }
            row.add(cell.toString());
            rows.add(List.copyOf(row));
        }
        return rows;
    }

    private static Path confinedFile(Path storage, String filename) throws IOException {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("filename must be set");
        }
        Path candidate = storage.resolve(filename).normalize();
        if (!candidate.startsWith(storage))
            throw new IllegalArgumentException("filename escapes storage");
        Path real = candidate.toRealPath();
        if (!real.startsWith(storage))
            throw new IllegalArgumentException("filename escapes storage");
        return real;
    }

    private static FunctionDeclaration declaration(
            String name, String description, Map<String, Schema> properties, String... required) {
        return FunctionDeclaration.builder()
                .name(name)
                .description(description)
                .parameters(
                        Schema.builder()
                                .type("OBJECT")
                                .properties(properties)
                                .required(required)
                                .build())
                .build();
    }

    private static Schema stringSchema() {
        return Schema.builder().type("STRING").build();
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Map<String, Object> error(String message) {
        return Map.of("status", "error", "error", message);
    }

    private static String safeMessage(Exception failure) {
        return failure.getMessage() == null || failure.getMessage().isBlank()
                ? failure.getClass().getSimpleName()
                : failure.getMessage();
    }
}
