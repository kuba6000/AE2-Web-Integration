package pl.kuba6000.ae2webintegration.doclet;

import static org.junit.jupiter.api.Assertions.*;

import java.io.StringWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.tools.DocumentationTool;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.SwaggerParseResult;

class OpenApiDocletTest {

    @TempDir
    Path directory;

    @Test
    void groupsOperationsByEndpointPackageWithOrderedTagDescriptions() throws Exception {
        List<Path> sources = fixture("public class Grids {}");
        for (String category : List.of("auth", "tracking", "crafting", "cpu", "grid")) {
            String className = Character.toUpperCase(category.charAt(0)) + category.substring(1);
            String packageName = "pl.kuba6000.ae2webintegration.core.http.endpoint." + category;
            sources.add(source(packageName.replace('.', '/') + "/" + className + ".java", """
                package %s;
                import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
                import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;
                /** Example operation.
                 * @response 200 {@link String} Result.
                 */
                @Endpoint(method = HttpMethod.GET, path = "/api/%s")
                public class %s {}
                """.formatted(packageName, category, className)));
        }
        Path output = directory.resolve("categories.json");
        Result result = generate(sources, output);
        assertTrue(result.success(), result.diagnostics());
        JsonObject document = JsonParser.parseString(Files.readString(output))
            .getAsJsonObject();
        List<String> names = List.of("Grids", "CPUs", "Crafting plans", "Crafting history", "Authentication");
        List<String> packages = List.of("grid", "cpu", "crafting", "tracking", "auth");
        assertNotNull(document.getAsJsonArray("tags"));
        assertEquals(
            names.size(),
            document.getAsJsonArray("tags")
                .size());
        for (int index = 0; index < names.size(); index++) {
            JsonObject tag = document.getAsJsonArray("tags")
                .get(index)
                .getAsJsonObject();
            assertEquals(
                names.get(index),
                tag.get("name")
                    .getAsString());
            assertFalse(
                tag.get("description")
                    .getAsString()
                    .isBlank());
            assertEquals(
                JsonParser.parseString("[\"" + names.get(index) + "\"]"),
                document.getAsJsonObject("paths")
                    .getAsJsonObject("/api/" + packages.get(index))
                    .getAsJsonObject("get")
                    .getAsJsonArray("tags"));
        }
        SwaggerParseResult parsed = new OpenAPIV3Parser().readContents(Files.readString(output));
        assertTrue(
            parsed.getMessages()
                .isEmpty(),
            parsed.getMessages()
                .toString());
    }

    @Test
    void generatesWholeResponsesAndDeterministicValidOpenApi() throws Exception {
        List<Path> sources = fixture("""
            /** Lists grids.
             * <p>Returns permitted grids.
             * @response 200 {@link Response} Accessible grids.
             * @response 400 {@link ErrorResponse} Invalid request.
             * @response 204
             */
            @Endpoint(method = HttpMethod.GET, path = "/api/grids")
            public class Grids {
                public record Response(String status, java.util.List<GridInfo> data) {}
                public record ErrorResponse(String status, @org.jetbrains.annotations.Nullable Void data) {}
                public record GridInfo(StableKey key, int cpuCount, String owner, boolean isOwned,
                    boolean isTrackingEnabled, java.util.Map<java.util.UUID, java.util.List<Source>> accessSources) {}
                public record Source(Player player, @org.jetbrains.annotations.Nullable String side) {}
                public static class Player {
                    public java.util.UUID uuid;
                    private String name;
                    public transient Object ignored = "hidden";
                    public static Object global = "hidden";
                }
            }
            """);
        Path output = directory.resolve("openapi.json");
        Result first = generate(sources, output);
        assertTrue(first.success(), first.diagnostics());
        String json = Files.readString(output);
        SwaggerParseResult parsed = new OpenAPIV3Parser().readContents(json);
        assertNotNull(parsed.getOpenAPI());
        assertTrue(
            parsed.getMessages()
                .isEmpty(),
            parsed.getMessages()
                .toString());
        JsonObject document = JsonParser.parseString(json)
            .getAsJsonObject();
        assertEquals(
            "3.1.0",
            document.get("openapi")
                .getAsString());
        assertEquals(
            "test-revision",
            document.getAsJsonObject("info")
                .get("version")
                .getAsString());
        JsonObject operation = document.getAsJsonObject("paths")
            .getAsJsonObject("/api/grids")
            .getAsJsonObject("get");
        assertEquals(
            "grids",
            operation.get("operationId")
                .getAsString());
        assertTrue(
            document.getAsJsonObject("components")
                .getAsJsonObject("schemas")
                .has("GridsResponse"));
        assertFalse(json.contains("fixture."));
        JsonObject responses = operation.getAsJsonObject("responses");
        JsonObject response = dereference(
            document,
            responses.getAsJsonObject("200")
                .getAsJsonObject("content")
                .getAsJsonObject("application/json")
                .getAsJsonObject("schema"));
        JsonObject properties = response.getAsJsonObject("properties");
        assertEquals(
            "string",
            properties.getAsJsonObject("status")
                .get("type")
                .getAsString());
        JsonObject grid = dereference(
            document,
            properties.getAsJsonObject("data")
                .getAsJsonObject("items"));
        JsonObject gridProperties = grid.getAsJsonObject("properties");
        assertEquals(
            "string",
            gridProperties.getAsJsonObject("key")
                .get("type")
                .getAsString());
        assertEquals(
            "integer",
            gridProperties.getAsJsonObject("cpuCount")
                .get("type")
                .getAsString());
        JsonObject sourcesSchema = gridProperties.getAsJsonObject("accessSources");
        assertEquals(
            "string",
            sourcesSchema.getAsJsonObject("propertyNames")
                .get("type")
                .getAsString());
        assertEquals(
            "uuid",
            sourcesSchema.getAsJsonObject("propertyNames")
                .get("format")
                .getAsString());
        assertEquals(
            "object",
            sourcesSchema.get("type")
                .getAsString());
        JsonObject source = dereference(
            document,
            sourcesSchema.getAsJsonObject("additionalProperties")
                .getAsJsonObject("items"));
        assertEquals(
            "null",
            source.getAsJsonObject("properties")
                .getAsJsonObject("side")
                .getAsJsonArray("anyOf")
                .get(1)
                .getAsJsonObject()
                .get("type")
                .getAsString());
        assertTrue(
            source.getAsJsonArray("required")
                .toString()
                .contains("side"));
        JsonObject player = dereference(
            document,
            source.getAsJsonObject("properties")
                .getAsJsonObject("player"));
        assertEquals(
            2,
            player.getAsJsonObject("properties")
                .size());
        assertEquals(
            "uuid",
            player.getAsJsonObject("properties")
                .getAsJsonObject("uuid")
                .get("format")
                .getAsString());
        JsonObject error = dereference(
            document,
            responses.getAsJsonObject("400")
                .getAsJsonObject("content")
                .getAsJsonObject("application/json")
                .getAsJsonObject("schema"));
        assertEquals(
            "null",
            error.getAsJsonObject("properties")
                .getAsJsonObject("data")
                .get("type")
                .getAsString());
        assertFalse(
            responses.getAsJsonObject("204")
                .has("content"));
        assertEquals(
            2,
            document.getAsJsonArray("security")
                .size());
        assertEquals(
            "authenticationToken",
            document.getAsJsonObject("components")
                .getAsJsonObject("securitySchemes")
                .getAsJsonObject("cookieAuth")
                .get("name")
                .getAsString());
        assertTrue(generate(sources, output).success());
        assertEquals(json, Files.readString(output));

        Path classes = directory.resolve("classes");
        Files.createDirectories(classes);
        try (StandardJavaFileManager manager = ToolProvider.getSystemJavaCompiler()
            .getStandardFileManager(null, null, null)) {
            assertTrue(
                ToolProvider.getSystemJavaCompiler()
                    .getTask(
                        null,
                        manager,
                        null,
                        List.of("-d", classes.toString()),
                        null,
                        manager.getJavaFileObjectsFromPaths(sources))
                    .call());
        }
        try (URLClassLoader loader = new URLClassLoader(
            new URL[] { classes.toUri()
                .toURL() })) {
            Class<?> sourceType = loader.loadClass("fixture.Grids$Source");
            String sourceJson = """
                {"player":{"uuid":"00000000-0000-0000-0000-000000000001","name":"Player"},"side":null}
                """;
            Gson gson = new GsonBuilder().serializeNulls()
                .create();
            Object sourceValue = gson.fromJson(sourceJson, sourceType);
            JsonObject serialized = gson.toJsonTree(sourceValue)
                .getAsJsonObject();
            assertEquals(
                source.getAsJsonObject("properties")
                    .keySet(),
                serialized.keySet());
            assertEquals(
                player.getAsJsonObject("properties")
                    .keySet(),
                serialized.getAsJsonObject("player")
                    .keySet());
            assertEquals(JsonParser.parseString(sourceJson), serialized);
        }
    }

    @ParameterizedTest
    @CsvSource({ "'@response nope',Malformed", "'@response 200 {@link Missing}',Unresolved",
        "'@response 204 {@link String}',body", "'@response 200 {@link java.time.Instant}',Unsupported",
        "'@queryParam key identifier',Unsupported", "'@response 200 {@link java.util.List}',Unsupported",
        "'@response 200 {@link Object}',Unsupported" })
    void rejectsInvalidContractsWithoutReplacingOutput(String tag, String error) throws Exception {
        List<Path> sources = fixture(
            "/** Endpoint.\n * " + tag
                + "\n */\n"
                + "@Endpoint(method=HttpMethod.GET, path=\"/api/grids\") public class Grids {}\n");
        Path output = directory.resolve("openapi.json");
        Files.writeString(output, "previous contract");
        Result result = generate(sources, output);
        assertFalse(result.success());
        assertTrue(
            result.diagnostics()
                .contains(error),
            result.diagnostics());
        assertEquals("previous contract", Files.readString(output));
    }

    @Test
    void rejectsDuplicateRoutesAndResponseStatuses() throws Exception {
        List<Path> sources = fixture("""
            /** Endpoint.
             * @response 200
             */
            @Endpoint(method=HttpMethod.GET, path="/api/grids") public class Grids {
                /** Another endpoint.
                 * @response 200
                 */
                @Endpoint(method=HttpMethod.GET, path="/api/grids") public static class Duplicate {}
            }
            """);
        Result duplicate = generate(sources, directory.resolve("routes.json"));
        assertFalse(duplicate.success());
        assertTrue(
            duplicate.diagnostics()
                .contains("Duplicate operation"),
            duplicate.diagnostics());
        sources = fixture("""
            /** Endpoint.
             * @response 200 First.
             * @response 200 Second.
             */
            @Endpoint(method=HttpMethod.GET, path="/api/grids") public class Grids {}
            """);
        Result responses = generate(sources, directory.resolve("responses.json"));
        assertFalse(responses.success());
        assertTrue(
            responses.diagnostics()
                .contains("Duplicate @response"),
            responses.diagnostics());
    }

    @Test
    void rejectsCustomEnumAdaptersInsteadOfClaimingDefaultGsonShape() throws Exception {
        List<Path> sources = fixture("""
            /** Endpoint.
             * @response 200 {@link State}
             */
            @Endpoint(method=HttpMethod.GET, path="/api/grids") public class Grids {
                @com.google.gson.annotations.JsonAdapter(Object.class)
                public enum State { READY }
            }
            """);
        sources.add(source("com/google/gson/annotations/JsonAdapter.java", """
            package com.google.gson.annotations;
            public @interface JsonAdapter { Class<?> value(); }
            """));
        Result result = generate(sources, directory.resolve("enum.json"));
        assertFalse(result.success());
        assertTrue(
            result.diagnostics()
                .contains("adapter"),
            result.diagnostics());
    }

    @Test
    void rejectsAmbiguousSchemaNames() throws Exception {
        List<Path> sources = fixture("""
            /** Endpoint.
             * @response 200 {@link Response}
             */
            @Endpoint(method=HttpMethod.GET, path="/api/grids") public class Grids {
                public record Response(first.GridInfo first, second.GridInfo second) {}
            }
            """);
        sources.add(source("first/GridInfo.java", "package first; public record GridInfo(String name) {}"));
        sources.add(source("second/GridInfo.java", "package second; public record GridInfo(int count) {}"));
        Result result = generate(sources, directory.resolve("ambiguous-schema.json"));
        assertFalse(result.success());
        assertTrue(
            result.diagnostics()
                .contains("Ambiguous schema name: GridInfo"),
            result.diagnostics());
    }

    @Test
    void rejectsAmbiguousOperationNames() throws Exception {
        List<Path> sources = fixture("""
            /** Endpoint.
             * @response 204
             */
            @Endpoint(method=HttpMethod.GET, path="/api/grids") public class Grids {}
            """);
        sources.add(source("other/Grids.java", """
            package other;
            import pl.kuba6000.ae2webintegration.core.http.contract.*;
            /** Another endpoint.
             * @response 204
             */
            @Endpoint(method=HttpMethod.GET, path="/api/other") public class Grids {}
            """));
        Result result = generate(sources, directory.resolve("ambiguous-operation.json"));
        assertFalse(result.success());
        assertTrue(
            result.diagnostics()
                .contains("Ambiguous operationId: grids"),
            result.diagnostics());
    }

    @Test
    void documentsDtoFieldsAndRecordComponentsFromJavadoc() throws Exception {
        List<Path> sources = fixture("""
            /** Lists players.
             * @response 200 {@link Response}
             */
            @Endpoint(method=HttpMethod.GET, path="/api/grids") public class Grids {
                /** List result.
                 * @param player the player described by this response
                 * @param count number of matching grids; {@code 0} when none are accessible
                 */
                public record Response(Player player, int count) {}
                /** A Minecraft player. */
                public static class Player {
                    /** Persistent Minecraft UUID. */
                    public java.util.UUID uuid;
                    /** Last known username. */
                    public String name;
                }
            }
            """);
        Path output = directory.resolve("descriptions.json");
        Result result = generate(sources, output);
        assertTrue(result.success(), result.diagnostics());
        JsonObject schemas = JsonParser.parseString(Files.readString(output))
            .getAsJsonObject()
            .getAsJsonObject("components")
            .getAsJsonObject("schemas");
        JsonObject response = schemas.getAsJsonObject("GridsResponse");
        assertEquals(
            "List result.",
            response.get("description")
                .getAsString());
        JsonObject properties = response.getAsJsonObject("properties");
        assertEquals(
            "the player described by this response",
            properties.getAsJsonObject("player")
                .get("description")
                .getAsString());
        assertEquals(
            "number of matching grids; `0` when none are accessible",
            properties.getAsJsonObject("count")
                .get("description")
                .getAsString());
        JsonObject player = schemas.getAsJsonObject("GridsPlayer");
        assertEquals(
            "A Minecraft player.",
            player.get("description")
                .getAsString());
        assertEquals(
            "Persistent Minecraft UUID.",
            player.getAsJsonObject("properties")
                .getAsJsonObject("uuid")
                .get("description")
                .getAsString());
        assertEquals(
            "Last known username.",
            player.getAsJsonObject("properties")
                .getAsJsonObject("name")
                .get("description")
                .getAsString());
    }

    @Test
    void buildsResponseExamplesFromFieldAndRecordHints() throws Exception {
        List<Path> sources = fixture("""
            /** Lists grids.
             * @response 200 {@link Response}
             */
            @Endpoint(method=HttpMethod.GET, path="/api/grids") public class Grids {
                /** Result.
                 * @example status OK
                 * @example count 3
                 * @example enabled true
                 * @example note null
                 * @keyExample accessSources 095be615-a8ad-4c33-8e9c-c7612fbf6c9f
                 */
                public record Response(String status, int count, boolean enabled,
                    @org.jetbrains.annotations.Nullable String note,
                    java.util.Map<java.util.UUID, java.util.List<Player>> accessSources) {}
                public static class Player {
                    /** Player identifier.
                     * @example 095be615-a8ad-4c33-8e9c-c7612fbf6c9f
                     */
                    public java.util.UUID uuid;
                    /** Username.
                     * @example ExamplePlayer
                     */
                    public String name;
                }
            }
            """);
        Path output = directory.resolve("examples.json");
        Result result = generate(sources, output);
        assertTrue(result.success(), result.diagnostics());
        JsonObject document = JsonParser.parseString(Files.readString(output))
            .getAsJsonObject();
        JsonObject media = document.getAsJsonObject("paths")
            .getAsJsonObject("/api/grids")
            .getAsJsonObject("get")
            .getAsJsonObject("responses")
            .getAsJsonObject("200")
            .getAsJsonObject("content")
            .getAsJsonObject("application/json");
        assertEquals(JsonParser.parseString("""
            {"status":"OK","count":3,"enabled":true,"note":null,
             "accessSources":{"095be615-a8ad-4c33-8e9c-c7612fbf6c9f":[
                 {"uuid":"095be615-a8ad-4c33-8e9c-c7612fbf6c9f","name":"ExamplePlayer"}]}}
            """), media.get("example"));
        JsonObject properties = dereference(document, media.getAsJsonObject("schema")).getAsJsonObject("properties");
        assertEquals(
            "OK",
            properties.getAsJsonObject("status")
                .getAsJsonArray("examples")
                .get(0)
                .getAsString());
        assertEquals(
            "095be615-a8ad-4c33-8e9c-c7612fbf6c9f",
            properties.getAsJsonObject("accessSources")
                .getAsJsonObject("propertyNames")
                .getAsJsonArray("examples")
                .get(0)
                .getAsString());
        SwaggerParseResult parsed = new OpenAPIV3Parser().readContents(Files.readString(output));
        assertNotNull(parsed.getOpenAPI());
        assertTrue(
            parsed.getMessages()
                .isEmpty(),
            parsed.getMessages()
                .toString());
    }

    @Test
    void responseExamplesOverrideSharedErrorFieldExamples() throws Exception {
        List<Path> sources = fixture("""
            /** Lists grids.
             * @response 401 {@link Error} Missing credentials.
             * @response 500 {@link Error} Unexpected failure.
             * @responseExample 401 {"status":"UNAUTHORIZED","data":null}
             * @responseExample 500 {"status":"INTERNAL_ERROR","data":null}
             */
            @Endpoint(method=HttpMethod.GET, path="/api/grids") public class Grids {
                /** Error.
                 * @example status FALLBACK
                 */
                public record Error(String status, Void data) {}
            }
            """);
        Path output = directory.resolve("error-examples.json");
        Result result = generate(sources, output);
        assertTrue(result.success(), result.diagnostics());
        JsonObject responses = JsonParser.parseString(Files.readString(output))
            .getAsJsonObject()
            .getAsJsonObject("paths")
            .getAsJsonObject("/api/grids")
            .getAsJsonObject("get")
            .getAsJsonObject("responses");
        assertEquals(
            JsonParser.parseString("{\"status\":\"UNAUTHORIZED\",\"data\":null}"),
            responses.getAsJsonObject("401")
                .getAsJsonObject("content")
                .getAsJsonObject("application/json")
                .get("example"));
        assertEquals(
            JsonParser.parseString("{\"status\":\"INTERNAL_ERROR\",\"data\":null}"),
            responses.getAsJsonObject("500")
                .getAsJsonObject("content")
                .getAsJsonObject("application/json")
                .get("example"));
    }

    @ParameterizedTest
    @CsvSource({ "'{\"status\":true,\"data\":null}'", "'{\"status\":\"UNAUTHORIZED\"}'",
        "'{\"status\":\"UNAUTHORIZED\",\"data\":42}'" })
    void rejectsResponseExamplesThatDoNotMatchTheirSchema(String example) throws Exception {
        List<Path> sources = fixture("""
            /** Endpoint.
             * @response 401 {@link Error}
             * @responseExample 401 %s
             */
            @Endpoint(method=HttpMethod.GET, path="/api/grids") public class Grids {
                public record Error(String status, Void data) {}
            }
            """.formatted(example));
        Path output = directory.resolve("invalid-example.json");
        Files.writeString(output, "previous contract");
        Result result = generate(sources, output);
        assertFalse(result.success());
        assertTrue(
            result.diagnostics()
                .contains("example does not match"),
            result.diagnostics());
        assertEquals("previous contract", Files.readString(output));
    }

    @Test
    void generatesFiniteExamplesForRecursiveSchemas() throws Exception {
        List<Path> sources = fixture("""
            /** Endpoint.
             * @response 200 {@link Node}
             * @response 201 {@link Endless}
             */
            @Endpoint(method=HttpMethod.GET, path="/api/grids") public class Grids {
                public record Node(@org.jetbrains.annotations.Nullable Node next, java.util.List<Node> children) {}
                public record Endless(Endless next) {}
            }
            """);
        Path output = directory.resolve("recursive-examples.json");
        Result result = generate(sources, output);
        assertTrue(result.success(), result.diagnostics());
        JsonObject responses = JsonParser.parseString(Files.readString(output))
            .getAsJsonObject()
            .getAsJsonObject("paths")
            .getAsJsonObject("/api/grids")
            .getAsJsonObject("get")
            .getAsJsonObject("responses");
        assertEquals(
            JsonParser.parseString("{\"next\":null,\"children\":[]}"),
            responses.getAsJsonObject("200")
                .getAsJsonObject("content")
                .getAsJsonObject("application/json")
                .get("example"));
        assertFalse(
            responses.getAsJsonObject("201")
                .getAsJsonObject("content")
                .getAsJsonObject("application/json")
                .has("example"));
    }

    @ParameterizedTest
    @CsvSource({ "'@example count 1.5',integer", "'@example enabled yes',boolean", "'@example uuid not-a-uuid',UUID",
        "'@keyExample count anything',map", "'@example missing 1',component",
        "'@example label \"unterminated',@example" })
    void rejectsInvalidFieldExamples(String tag, String diagnostic) throws Exception {
        List<Path> sources = fixture("""
            /** Endpoint.
             * @response 200 {@link Response}
             */
            @Endpoint(method=HttpMethod.GET, path="/api/grids") public class Grids {
                /** Result.
                 * %s
                 */
                public record Response(int count, boolean enabled, java.util.UUID uuid, String label) {}
            }
            """.formatted(tag));
        Result result = generate(sources, directory.resolve("invalid-field-example.json"));
        assertFalse(result.success());
        assertTrue(
            result.diagnostics()
                .contains(diagnostic),
            result.diagnostics());
    }

    @Test
    void supportsMultilineResponseDescriptions() throws Exception {
        List<Path> sources = fixture("""
            /** Endpoint.
             * @response 204 Completed action.
             *     Additional explanation.
             */
            @Endpoint(method=HttpMethod.GET, path="/api/grids") public class Grids {}
            """);
        Result result = generate(sources, directory.resolve("multiline.json"));
        assertTrue(result.success(), result.diagnostics());
    }

    @Test
    void derivesInheritedPathBindingsAndStrictOptionalInputWithoutChangingResponses() throws Exception {
        List<Path> sources = fixture("""
            /** Updates settings.
             * @pathParam gridKey Stable grid identifier.
             * @pathParam planId Existing plan identifier.
             * @response 200 {@link Payload} Updated settings.
             */
            @Endpoint(method=HttpMethod.POST, path="/api/grids/{gridKey}/plans/{planId}")
            public class Grids extends Base {
                @PathParam("planId") private int plan;
                /** Submitted settings. */
                @Body private Payload body;
                /**
                 * Input values.
                 * @param name Display name.
                 * @param enabled Whether tracking is enabled.
                 * @param cpuKey Optional CPU selection.
                 * @example name Example
                 * @example enabled true
                 * @example cpuKey AAAAAAAAAAAAAAAAAAAAAA
                 * @example label Custom label
                 */
                public record Payload(String name, @OptionalInput @org.jetbrains.annotations.Nullable Boolean enabled,
                    @OptionalInput @org.jetbrains.annotations.Nullable StableKey cpuKey,
                    @OptionalInput @org.jetbrains.annotations.Nullable String label) {}
            }
            class Base {
                @PathParam("gridKey") protected StableKey identity;
                @PathParam("unused") protected int ignored;
            }
            """);
        Path output = directory.resolve("input.json");
        Result result = generate(sources, output);
        assertTrue(result.success(), result.diagnostics());
        JsonObject document = JsonParser.parseString(Files.readString(output))
            .getAsJsonObject();
        JsonObject operation = document.getAsJsonObject("paths")
            .getAsJsonObject("/api/grids/{gridKey}/plans/{planId}")
            .getAsJsonObject("post");
        assertEquals(
            2,
            operation.getAsJsonArray("parameters")
                .size());
        JsonObject grid = operation.getAsJsonArray("parameters")
            .get(0)
            .getAsJsonObject();
        assertEquals(
            "gridKey",
            grid.get("name")
                .getAsString());
        assertEquals(
            "path",
            grid.get("in")
                .getAsString());
        assertTrue(
            grid.get("required")
                .getAsBoolean());
        assertEquals(
            "string",
            grid.getAsJsonObject("schema")
                .get("type")
                .getAsString());
        assertEquals(
            "Stable grid identifier.",
            grid.get("description")
                .getAsString());
        assertEquals(
            "integer",
            operation.getAsJsonArray("parameters")
                .get(1)
                .getAsJsonObject()
                .getAsJsonObject("schema")
                .get("type")
                .getAsString());
        JsonObject body = operation.getAsJsonObject("requestBody");
        assertTrue(
            body.get("required")
                .getAsBoolean());
        assertEquals(
            "Submitted settings.",
            body.get("description")
                .getAsString());
        JsonObject input = dereference(
            document,
            body.getAsJsonObject("content")
                .getAsJsonObject("application/json")
                .getAsJsonObject("schema"));
        assertEquals(JsonParser.parseString("[\"name\"]"), input.getAsJsonArray("required"));
        assertFalse(
            input.get("additionalProperties")
                .getAsBoolean());
        assertEquals(
            "boolean",
            input.getAsJsonObject("properties")
                .getAsJsonObject("enabled")
                .get("type")
                .getAsString());
        assertEquals(
            "string",
            input.getAsJsonObject("properties")
                .getAsJsonObject("cpuKey")
                .get("type")
                .getAsString());
        assertEquals(
            "Whether tracking is enabled.",
            input.getAsJsonObject("properties")
                .getAsJsonObject("enabled")
                .get("description")
                .getAsString());
        JsonObject inputExample = body.getAsJsonObject("content")
            .getAsJsonObject("application/json")
            .getAsJsonObject("example");
        assertEquals(
            "Example",
            inputExample.get("name")
                .getAsString());
        assertTrue(
            inputExample.get("enabled")
                .getAsBoolean());
        assertEquals(
            "AAAAAAAAAAAAAAAAAAAAAA",
            inputExample.get("cpuKey")
                .getAsString());
        assertEquals(
            "Custom label",
            inputExample.get("label")
                .getAsString());
        assertEquals(
            "string",
            input.getAsJsonObject("properties")
                .getAsJsonObject("label")
                .get("type")
                .getAsString());
        JsonObject response = dereference(
            document,
            operation.getAsJsonObject("responses")
                .getAsJsonObject("200")
                .getAsJsonObject("content")
                .getAsJsonObject("application/json")
                .getAsJsonObject("schema"));
        assertEquals(
            4,
            response.getAsJsonArray("required")
                .size());
        assertTrue(
            response.getAsJsonObject("properties")
                .getAsJsonObject("enabled")
                .has("anyOf"));
        assertFalse(operation.has("security"));
        SwaggerParseResult parsed = new OpenAPIV3Parser().readContents(Files.readString(output));
        assertTrue(
            parsed.getMessages()
                .isEmpty(),
            parsed.getMessages()
                .toString());
    }

    @Test
    void publicEndpointOverridesAuthenticationRequirements() throws Exception {
        List<Path> sources = fixture("""
            /** Public operation.
             * @response 204
             */
            @Endpoint(method=HttpMethod.POST, path="/api/auth/login", authenticated=false)
            public class Grids {}
            """);
        Path output = directory.resolve("public.json");
        Result result = generate(sources, output);
        assertTrue(result.success(), result.diagnostics());
        JsonObject operation = JsonParser.parseString(Files.readString(output))
            .getAsJsonObject()
            .getAsJsonObject("paths")
            .getAsJsonObject("/api/auth/login")
            .getAsJsonObject("post");
        assertEquals(
            0,
            operation.getAsJsonArray("security")
                .size());
    }

    @ParameterizedTest
    @CsvSource({ "'/api/grids/{gridKey}', '', '@PathParam(\"gridKey\") StableKey key;', pathParam",
        "'/api/grids/{gridKey}', '@pathParam gridKey Identifier.', '', PathParam",
        "'/api/grids', '@pathParam gridKey Identifier.', '', pathParam",
        "'/api/grids/{gridKey}', '@pathParam gridKey Identifier.', '@PathParam(\"gridKey\") StableKey a; @PathParam(\"gridKey\") StableKey b;', Duplicate",
        "'/api/grids/{gridKey}', '@pathParam gridKey Identifier.', '@PathParam(\"gridKey\") static StableKey key;', Static",
        "'/api/grids/x{gridKey}', '@pathParam gridKey Identifier.', '@PathParam(\"gridKey\") StableKey key;', placeholder",
        "'/api/grids/{gridKey}', '@pathParam gridKey Identifier.', '@PathParam(\"gridKey\") java.util.List<String> key;', Unsupported",
        "'/api/grids/{gridKey}', '@pathParam gridKey Identifier.', '@PathParam(\"gridKey\") Payload key;', Unsupported",
        "'/api/grids', '', '@Body Payload a; @Body Payload b;', Multiple",
        "'/api/grids', '', '@Body String a;', concrete", "'/api/grids', '', '@Body static Payload a;', Static" })
    void rejectsInconsistentInputBindings(String path, String tag, String fields, String diagnostic) throws Exception {
        List<Path> sources = fixture(
            "/** Endpoint.\n * " + tag
                + "\n * @response 204\n */\n"
                + "@Endpoint(method=HttpMethod.POST, path=\""
                + path
                + "\") public class Grids { "
                + fields
                + " public record Payload(String value) {} }");
        Result result = generate(sources, directory.resolve("invalid-input.json"));
        assertFalse(result.success());
        assertTrue(
            result.diagnostics()
                .contains(diagnostic),
            result.diagnostics());
    }

    @Test
    void concreteCollectionsPreserveItemGraphsAndContainerNullability() throws Exception {
        List<Path> sources = fixture("""
            /** Existing DTO graph.
             * @response 200 {@link Response} Crafting details.
             */
            @Endpoint(method=HttpMethod.GET, path="/api/grids") public class Grids {
                public record Response(java.util.@org.jetbrains.annotations.Nullable ArrayList<Item> items,
                    java.util.HashSet<Position> positions) {}
                public record Item(String name, java.util.ArrayList<Timing> timings) {}
                public record Timing(long start, long end) {}
                public record Position(String dimension, int x) {}
            }
            """);
        Path output = directory.resolve("collections.json");
        Result result = generate(sources, output);
        assertTrue(result.success(), result.diagnostics());
        JsonObject document = JsonParser.parseString(Files.readString(output))
            .getAsJsonObject();
        JsonObject media = document.getAsJsonObject("paths")
            .getAsJsonObject("/api/grids")
            .getAsJsonObject("get")
            .getAsJsonObject("responses")
            .getAsJsonObject("200")
            .getAsJsonObject("content")
            .getAsJsonObject("application/json");
        JsonObject response = dereference(document, media.getAsJsonObject("schema"));
        JsonObject properties = response.getAsJsonObject("properties");
        JsonObject items = properties.getAsJsonObject("items");
        assertEquals(
            "null",
            items.getAsJsonArray("anyOf")
                .get(1)
                .getAsJsonObject()
                .get("type")
                .getAsString());
        JsonObject array = items.getAsJsonArray("anyOf")
            .get(0)
            .getAsJsonObject();
        assertEquals(
            "array",
            array.get("type")
                .getAsString());
        JsonObject item = dereference(document, array.getAsJsonObject("items"));
        JsonObject timings = item.getAsJsonObject("properties")
            .getAsJsonObject("timings");
        assertEquals(
            "array",
            timings.get("type")
                .getAsString());
        assertEquals(
            "integer",
            dereference(document, timings.getAsJsonObject("items")).getAsJsonObject("properties")
                .getAsJsonObject("start")
                .get("type")
                .getAsString());
        assertEquals(
            "array",
            properties.getAsJsonObject("positions")
                .get("type")
                .getAsString());
        assertEquals(
            "string",
            dereference(
                document,
                properties.getAsJsonObject("positions")
                    .getAsJsonObject("items"))
                .getAsJsonObject("properties")
                .getAsJsonObject("dimension")
                .get("type")
                .getAsString());
        assertTrue(
            media.getAsJsonObject("example")
                .get("positions")
                .isJsonArray());
        SwaggerParseResult parsed = new OpenAPIV3Parser().readContents(Files.readString(output));
        assertTrue(
            parsed.getMessages()
                .isEmpty(),
            parsed.getMessages()
                .toString());
    }

    @ParameterizedTest
    @CsvSource({ "invaliduser,true", "invalidpassword,true", "notonline,true", "INVALID_USER,false",
        "INVALID_PASSWORD,false", "NOT_ONLINE,false" })
    void enumSchemasAndExamplesUseSerializedWireNames(String status, boolean valid) throws Exception {
        List<Path> sources = fixture("""
            /** Auth operation.
             * @response 200 {@link Success} Successful request.
             * @response 401 {@link Failure} Authentication failed.
             * @responseExample 401 {"status":"%s","data":null}
             */
            @Endpoint(method=HttpMethod.POST, path="/api/auth/login", authenticated=false)
            public class Grids {
                public enum Status {
                    OK,
                    @com.google.gson.annotations.SerializedName("invaliduser") INVALID_USER,
                    @com.google.gson.annotations.SerializedName("invalidpassword") INVALID_PASSWORD,
                    @com.google.gson.annotations.SerializedName("notonline") NOT_ONLINE
                }
                /** @example status OK */
                public record Success(Status status, Void data) {}
                /** @example status invaliduser */
                public record Failure(Status status, Void data) {}
            }
            """.formatted(status));
        sources.add(source("com/google/gson/annotations/SerializedName.java", """
            package com.google.gson.annotations;
            @java.lang.annotation.Target(java.lang.annotation.ElementType.FIELD)
            public @interface SerializedName { String value(); String[] alternate() default {}; }
            """));
        Path output = directory.resolve("enum-wire.json");
        Result result = generate(sources, output);
        assertEquals(valid, result.success(), result.diagnostics());
        if (!valid) {
            assertTrue(
                result.diagnostics()
                    .contains("example does not match"),
                result.diagnostics());
            return;
        }
        JsonObject document = JsonParser.parseString(Files.readString(output))
            .getAsJsonObject();
        JsonObject responses = document.getAsJsonObject("paths")
            .getAsJsonObject("/api/auth/login")
            .getAsJsonObject("post")
            .getAsJsonObject("responses");
        JsonObject error = responses.getAsJsonObject("401")
            .getAsJsonObject("content")
            .getAsJsonObject("application/json");
        JsonObject schema = dereference(document, error.getAsJsonObject("schema"));
        assertEquals(
            JsonParser.parseString("[\"OK\",\"invaliduser\",\"invalidpassword\",\"notonline\"]"),
            schema.getAsJsonObject("properties")
                .getAsJsonObject("status")
                .getAsJsonArray("enum"));
        assertEquals(
            status,
            error.getAsJsonObject("example")
                .get("status")
                .getAsString());
        assertEquals(
            "OK",
            responses.getAsJsonObject("200")
                .getAsJsonObject("content")
                .getAsJsonObject("application/json")
                .getAsJsonObject("example")
                .get("status")
                .getAsString());
        SwaggerParseResult parsed = new OpenAPIV3Parser().readContents(Files.readString(output));
        assertTrue(
            parsed.getMessages()
                .isEmpty(),
            parsed.getMessages()
                .toString());
    }

    private static JsonObject dereference(JsonObject document, JsonObject schema) {
        if (!schema.has("$ref")) return schema;
        String reference = schema.get("$ref")
            .getAsString();
        return document.getAsJsonObject("components")
            .getAsJsonObject("schemas")
            .getAsJsonObject(reference.substring(reference.lastIndexOf('/') + 1));
    }

    private List<Path> fixture(String endpoint) throws Exception {
        List<Path> files = new ArrayList<>();
        files.add(source("pl/kuba6000/ae2webintegration/core/http/contract/Endpoint.java", """
            package pl.kuba6000.ae2webintegration.core.http.contract;
            public @interface Endpoint { HttpMethod method(); String path(); boolean authenticated() default true; }
            """));
        files.add(source("pl/kuba6000/ae2webintegration/core/http/contract/HttpMethod.java", """
            package pl.kuba6000.ae2webintegration.core.http.contract;
            public enum HttpMethod { GET, POST }
            """));
        for (String name : List.of("PathParam", "Body", "OptionalInput")) {
            files.add(
                source(
                    "pl/kuba6000/ae2webintegration/core/http/contract/" + name + ".java",
                    "package pl.kuba6000.ae2webintegration.core.http.contract;\n"
                        + "@java.lang.annotation.Target(java.lang.annotation.ElementType.FIELD)\n"
                        + "public @interface "
                        + name
                        + " { "
                        + (name.equals("PathParam") ? "String value();" : "")
                        + " }"));
        }
        files.add(source("pl/kuba6000/ae2webintegration/core/identity/StableKey.java", """
            package pl.kuba6000.ae2webintegration.core.identity;
            public final class StableKey { private long internal; }
            """));
        files.add(
            source(
                "org/jetbrains/annotations/Nullable.java",
                """
                    package org.jetbrains.annotations;
                    @java.lang.annotation.Target({java.lang.annotation.ElementType.TYPE_USE, java.lang.annotation.ElementType.FIELD,
                        java.lang.annotation.ElementType.RECORD_COMPONENT})
                    public @interface Nullable {}
                    """));
        files.add(source("fixture/Grids.java", """
            package fixture;
            import pl.kuba6000.ae2webintegration.core.http.contract.*;
            import pl.kuba6000.ae2webintegration.core.identity.StableKey;
            """ + endpoint));
        return files;
    }

    private Path source(String name, String source) throws Exception {
        Path file = directory.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        return file;
    }

    private Result generate(List<Path> sources, Path output) throws Exception {
        DocumentationTool tool = ToolProvider.getSystemDocumentationTool();
        StringWriter diagnostics = new StringWriter();
        try (StandardJavaFileManager manager = tool.getStandardFileManager(null, null, null)) {
            boolean success = tool
                .getTask(
                    diagnostics,
                    manager,
                    null,
                    OpenApiDoclet.class,
                    List.of("-quiet", "-private", "--output", output.toString(), "--api-version", "test-revision"),
                    manager.getJavaFileObjectsFromPaths(sources))
                .call();
            return new Result(success, diagnostics.toString());
        }
    }

    private record Result(boolean success, String diagnostics) {}
}
