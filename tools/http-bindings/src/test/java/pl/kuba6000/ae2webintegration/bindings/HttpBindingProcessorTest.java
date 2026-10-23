package pl.kuba6000.ae2webintegration.bindings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonParser;

import pl.kuba6000.ae2webintegration.core.AE2Controller.RequestContext;
import pl.kuba6000.ae2webintegration.core.WebPrincipal;
import pl.kuba6000.ae2webintegration.core.ae2request.async.IAsyncRequest;
import pl.kuba6000.ae2webintegration.core.http.InputValues;

class HttpBindingProcessorTest {

    @TempDir
    Path directory;

    @Test
    void endpointDeclarationAloneGeneratesDefaultedQueryBinding() throws Exception {
        assertCompiles("  @QueryParam(\"limit\") int limit = 200;");
    }

    @Test
    void separatelyCompiledCatalogCanConstructAPackageAccessibleNestedEndpoint() throws Exception {
        Compilation result = compileSource(
            "package fixture;\n" + "import pl.kuba6000.ae2webintegration.core.http.contract.*;\n"
                + "import pl.kuba6000.ae2webintegration.core.http.*;\n"
                + "import pl.kuba6000.ae2webintegration.core.ae2request.async.IAsyncRequest;\n"
                + "class Owner {\n"
                + " @Endpoint(method=HttpMethod.GET,path=\"/api/nested\")\n"
                + " static final class Nested extends IAsyncRequest { public void handle() { done(); } }\n"
                + "}\n"
                + "public final class Example {\n"
                + " public static Object create() {\n"
                + "  for (HttpBindingCatalog catalog : java.util.ServiceLoader.load(HttpBindingCatalog.class, Example.class.getClassLoader())) {\n"
                + "   HttpBinding binding = catalog.get(Owner.Nested.class);\n"
                + "   if (binding != null) return binding.factory().get();\n"
                + "  }\n"
                + "  throw new AssertionError(\"No catalog binding\");\n"
                + " }\n"
                + "}");
        assertTrue(
            result.success(),
            result.diagnostics()
                .getDiagnostics()
                .toString());
        try (URLClassLoader loader = new URLClassLoader(
            new URL[] { directory.toUri()
                .toURL() },
            getClass().getClassLoader())) {
            Object request = loader.loadClass("fixture.Example")
                .getMethod("create")
                .invoke(null);
            assertInstanceOf(IAsyncRequest.class, request);
        }
    }

    @Test
    void compilesNestedBodyShapesWithOptionalMembers() throws Exception {
        assertCompiles(
            "  @Body Input input;\n" + "  public record Input(long quantity, @OptionalInput Details details) {}\n"
                + "  public record Details(boolean enabled, String name) {}");
    }

    @Test
    void generatedBodyBindingDecodesOnlyStrictValidShapes() throws Exception {
        assertCompiles(
            "  @Body Input input;\n"
                + "  public static final class Input { long quantity; @OptionalInput Details details; }\n"
                + "  public static final class Details { boolean enabled; @org.jetbrains.annotations.NotNull String name; }\n"
                + "  public long quantity() { return input.quantity; }");
        try (URLClassLoader loader = new URLClassLoader(
            new URL[] { directory.toUri()
                .toURL() },
            getClass().getClassLoader())) {
            Class<?> endpoint = loader.loadClass("fixture.Example");
            Object request = endpoint.getConstructor()
                .newInstance();
            Method bind = endpoint.getMethod("bind", InputValues.class);
            bind.invoke(request, inputs("{\"quantity\":7}"));
            assertEquals(
                7L,
                endpoint.getMethod("quantity")
                    .invoke(request));
            bind.invoke(request, inputs("{\"quantity\":8,\"details\":{\"enabled\":true,\"name\":\"example\"}}"));
            assertEquals(
                8L,
                endpoint.getMethod("quantity")
                    .invoke(request));
            for (String invalid : Arrays.asList(
                "{\"quantity\":\"7\"}",
                "{\"quantity\":1.5}",
                "{\"quantity\":9223372036854775808}",
                "{\"quantity\":7,\"other\":true}",
                "{\"quantity\":7,\"details\":null}",
                "{\"quantity\":7,\"details\":{\"enabled\":1,\"name\":\"x\"}}")) {
                InvocationTargetException failure = assertThrows(
                    InvocationTargetException.class,
                    () -> bind.invoke(request, inputs(invalid)),
                    invalid);
                assertInstanceOf(IllegalArgumentException.class, failure.getCause());
            }
        }
    }

    @Test
    void projectedBodyShapesExcludeMarkedFieldsRecursivelyWithoutChangingOrdinaryShapes() throws Exception {
        assertCompiles("""
              @Body Input input;
              public static final class Input { Projected projected; Source ordinary; }
              @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
              @java.lang.annotation.Target(java.lang.annotation.ElementType.FIELD)
              public @interface Hidden {}
              @InputShape(value=Source.class, excludeFieldsAnnotatedWith=Hidden.class)
              public static final class Projected { long amount; Nested child; String adapterState; }
              public static final class Source { long amount; Nested child; @Hidden String secret; }
              public static final class Nested { boolean enabled; @Hidden String secret; }
              public long amount() { return input.projected.amount; }
            """);
        try (URLClassLoader loader = new URLClassLoader(
            new URL[] { directory.toUri()
                .toURL() },
            getClass().getClassLoader())) {
            Class<?> endpoint = loader.loadClass("fixture.Example");
            Object request = endpoint.getConstructor()
                .newInstance();
            Method bind = endpoint.getMethod("bind", InputValues.class);
            String projected = "{\"amount\":7,\"child\":{\"enabled\":true}}";
            String ordinary = "{\"amount\":8,\"child\":{\"enabled\":false,\"secret\":\"nested\"},\"secret\":\"root\"}";
            bind.invoke(request, inputs("{\"projected\":" + projected + ",\"ordinary\":" + ordinary + "}"));
            assertEquals(
                7L,
                endpoint.getMethod("amount")
                    .invoke(request));
            for (String invalid : Arrays.asList(
                "{\"projected\":" + projected.replace("\"amount\":7", "\"amount\":7,\"secret\":\"x\"")
                    + ",\"ordinary\":"
                    + ordinary
                    + "}",
                "{\"projected\":" + projected.replace("\"enabled\":true", "\"enabled\":true,\"secret\":\"x\"")
                    + ",\"ordinary\":"
                    + ordinary
                    + "}",
                "{\"projected\":" + projected + ",\"ordinary\":" + projected + "}",
                "{\"projected\":" + projected
                    + ",\"ordinary\":"
                    + ordinary.replace(",\"secret\":\"nested\"", "")
                    + "}")) {
                InvocationTargetException failure = assertThrows(
                    InvocationTargetException.class,
                    () -> bind.invoke(request, inputs(invalid)),
                    invalid);
                assertInstanceOf(IllegalArgumentException.class, failure.getCause());
            }
        }
    }

    @Test
    void rejectsNonObjectInputShapeTargetsAtCompilation() throws Exception {
        Compilation result = compile("""
              @Body Input input;
              @InputShape(value=int.class, excludeFieldsAnnotatedWith=Deprecated.class)
              public static final class Input {}
            """);
        assertFalse(result.success());
        assertTrue(
            result.diagnostics()
                .getDiagnostics()
                .stream()
                .anyMatch(
                    diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR && diagnostic.getMessage(null)
                        .contains("InputShape requires a class or record")));
    }

    private InputValues inputs(String body) {
        return new InputValues(
            new RequestContext(
                null,
                WebPrincipal.admin(),
                Collections.emptyMap(),
                JsonParser.parseString(body)
                    .getAsJsonObject()),
            "/api/example");
    }

    @Test
    void rejectsPrivateInputWithAFieldDiagnostic() throws Exception {
        Compilation result = compile("  @QueryParam(\"limit\") private int limit;");
        assertFalse(result.success());
        assertTrue(
            result.diagnostics()
                .getDiagnostics()
                .stream()
                .anyMatch(
                    diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR && diagnostic.getMessage(null)
                        .contains("accessible")
                        && diagnostic.getMessage(null)
                            .contains("limit")));
    }

    @Test
    void rejectsUnsupportedQueryTypesAtCompilation() throws Exception {
        Compilation result = compile("  @QueryParam(\"values\") java.util.List<String> values;");
        assertFalse(result.success());
        assertTrue(
            result.diagnostics()
                .getDiagnostics()
                .stream()
                .anyMatch(
                    diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR && diagnostic.getMessage(null)
                        .contains("Unsupported HTTP parameter type")));
    }

    @Test
    void rejectsAnInputWithConflictingTransportAnnotations() throws Exception {
        Compilation result = compile("  @QueryParam(\"value\") @PathParam(\"value\") int value;");
        assertFalse(result.success());
        assertTrue(
            result.diagnostics()
                .getDiagnostics()
                .stream()
                .anyMatch(
                    diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR && diagnostic.getMessage(null)
                        .contains("one transport annotation")));
    }

    private void assertCompiles(String inputs) throws Exception {
        Compilation result = compile(inputs);
        assertTrue(
            result.success(),
            result.diagnostics()
                .getDiagnostics()
                .toString());
    }

    private Compilation compile(String inputs) throws Exception {
        return compileSource(
            String.join(
                "\n",
                Arrays.asList(
                    "package fixture;",
                    "import pl.kuba6000.ae2webintegration.core.http.contract.*;",
                    "import pl.kuba6000.ae2webintegration.core.ae2request.async.IAsyncRequest;",
                    "@Endpoint(method=HttpMethod.GET,path=\"/api/example\")",
                    "public final class Example extends IAsyncRequest {",
                    inputs,
                    "  public void bind(pl.kuba6000.ae2webintegration.core.http.InputValues values) { Example_HttpBinding.bind(this, values); }",
                    "  public void handle() {}",
                    "}")));
    }

    private Compilation compileSource(String sourceText) throws Exception {
        Path source = directory.resolve("Example.java");
        Files.write(source, sourceText.getBytes(StandardCharsets.UTF_8));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler
            .getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            boolean compiled = compiler
                .getTask(
                    null,
                    files,
                    diagnostics,
                    Arrays.asList(
                        "-classpath",
                        System.getProperty("java.class.path"),
                        "-d",
                        directory.toString(),
                        "-processor",
                        "pl.kuba6000.ae2webintegration.bindings.HttpBindingProcessor",
                        "-AhttpBindings.catalog=fixture.GeneratedBindings"),
                    null,
                    files.getJavaFileObjects(source.toFile()))
                .call();
            return new Compilation(compiled, diagnostics);
        }
    }

    private record Compilation(boolean success, DiagnosticCollector<JavaFileObject> diagnostics) {}
}
