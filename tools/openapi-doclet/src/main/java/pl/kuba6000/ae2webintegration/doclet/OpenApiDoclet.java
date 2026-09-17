package pl.kuba6000.ae2webintegration.doclet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;
import com.sun.source.doctree.DocCommentTree;
import com.sun.source.doctree.DocTree;
import com.sun.source.doctree.LinkTree;
import com.sun.source.doctree.LiteralTree;
import com.sun.source.doctree.ParamTree;
import com.sun.source.doctree.UnknownBlockTagTree;
import com.sun.source.util.DocTreePath;

import jdk.javadoc.doclet.Doclet;
import jdk.javadoc.doclet.DocletEnvironment;
import jdk.javadoc.doclet.Reporter;

/** Generates a standalone OpenAPI contract from endpoint annotations and response Javadoc. */
public final class OpenApiDoclet implements Doclet {

    private record Category(String packageName, String name, String description) {}

    private static final List<Category> CATEGORIES = List.of(
        new Category("grid", "Grids", "Discover accessible ME grids, browse their items and manage grid settings."),
        new Category("cpu", "CPUs", "Inspect crafting CPUs and cancel their current work."),
        new Category("crafting", "Crafting plans", "Create, inspect, submit and discard crafting plans."),
        new Category("tracking", "Crafting history", "Browse recorded crafting jobs and their measurements."),
        new Category("auth", "Authentication", "Obtain or revoke session tokens and start account registration."));

    private static final String ENDPOINT = "pl.kuba6000.ae2webintegration.core.http.contract.Endpoint";
    private static final String PATH_PARAM = "pl.kuba6000.ae2webintegration.core.http.contract.PathParam";
    private static final String BODY = "pl.kuba6000.ae2webintegration.core.http.contract.Body";
    private static final String OPTIONAL_INPUT = "pl.kuba6000.ae2webintegration.core.http.contract.OptionalInput";
    private static final String STABLE_KEY = "pl.kuba6000.ae2webintegration.core.identity.StableKey";
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting()
        .disableHtmlEscaping()
        .serializeNulls()
        .setStrictness(Strictness.STRICT)
        .create();
    private final Map<String, Object> schemas = new TreeMap<>();
    private final Map<String, String> schemaTypes = new TreeMap<>();
    private Reporter reporter;
    private DocletEnvironment environment;
    private Path output;
    private String version;

    @Override
    public void init(Locale locale, Reporter reporter) {
        this.reporter = reporter;
    }

    @Override
    public String getName() {
        return "OpenAPI";
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.RELEASE_17;
    }

    @Override
    public Set<? extends Option> getSupportedOptions() {
        return Set.of(
            option("--output", "OpenAPI JSON output file", value -> output = Path.of(value)),
            option("--api-version", "Core revision or release described by this contract", value -> version = value));
    }

    private static Option option(String name, String description, Consumer<String> action) {
        return new Option() {

            @Override
            public int getArgumentCount() {
                return 1;
            }

            @Override
            public String getDescription() {
                return description;
            }

            @Override
            public Kind getKind() {
                return Kind.STANDARD;
            }

            @Override
            public List<String> getNames() {
                return List.of(name);
            }

            @Override
            public String getParameters() {
                return "<value>";
            }

            @Override
            public boolean process(String option, List<String> arguments) {
                action.accept(arguments.get(0));
                return true;
            }
        };
    }

    @Override
    public boolean run(DocletEnvironment environment) {
        this.environment = environment;
        schemas.clear();
        schemaTypes.clear();
        try {
            if (output == null || version == null || version.isBlank()) {
                throw new IllegalArgumentException("--output and --api-version are required");
            }
            Map<String, Map<String, Object>> paths = new TreeMap<>();
            Set<String> operationIds = new HashSet<>();
            Set<String> usedCategories = new HashSet<>();
            List<TypeElement> endpoints = environment.getIncludedElements()
                .stream()
                .filter(element -> element instanceof TypeElement)
                .map(element -> (TypeElement) element)
                .filter(element -> annotation(element, ENDPOINT) != null)
                .sorted(
                    Comparator.comparing(
                        element -> element.getQualifiedName()
                            .toString()))
                .toList();
            if (endpoints.isEmpty()) throw new IllegalArgumentException("No annotated endpoints included");
            for (TypeElement endpoint : endpoints) {
                AnnotationMirror route = annotation(endpoint, ENDPOINT);
                String path = annotationValue(route, "path").toString();
                String method = annotationValue(route, "method").toString()
                    .toLowerCase(Locale.ROOT);
                if (!path.startsWith("/api/") || path.contains("?")
                    || path.contains("#")
                    || path.chars()
                        .anyMatch(Character::isWhitespace)) {
                    throw problem(endpoint, "Unsupported API path: " + path);
                }
                if (!Set.of("get", "post", "put", "patch", "delete", "head", "options", "trace")
                    .contains(method)) {
                    throw problem(endpoint, "Unsupported HTTP method: " + method);
                }
                Map<String, Object> operations = paths.computeIfAbsent(path, ignored -> new TreeMap<>());
                Map<String, Object> operation = operation(endpoint, path, route);
                String packageName = environment.getElementUtils()
                    .getPackageOf(endpoint)
                    .getQualifiedName()
                    .toString();
                for (Category category : CATEGORIES) {
                    if (packageName
                        .equals("pl.kuba6000.ae2webintegration.core.http.endpoint." + category.packageName())) {
                        operation.put("tags", List.of(category.name()));
                        usedCategories.add(category.name());
                        break;
                    }
                }
                if (operations.putIfAbsent(method, operation) != null) {
                    throw problem(endpoint, "Duplicate operation: " + method.toUpperCase(Locale.ROOT) + " " + path);
                }
                String operationId = (String) operation.get("operationId");
                if (!operationIds.add(operationId)) {
                    throw problem(endpoint, "Ambiguous operationId: " + operationId);
                }
            }
            Map<String, Object> document = object(
                "openapi",
                "3.1.0",
                "info",
                object(
                    "title",
                    "AE2 Web Integration API",
                    "version",
                    version,
                    "description",
                    "Development API. Configured local access may bypass credentials; "
                        + "the server's trusted-client configuration determines that exception."),
                "paths",
                paths,
                "tags",
                CATEGORIES.stream()
                    .filter(category -> usedCategories.contains(category.name()))
                    .map(category -> object("name", category.name(), "description", category.description()))
                    .toList(),
                "security",
                List.of(object("bearerAuth", List.of()), object("cookieAuth", List.of())),
                "components",
                object(
                    "schemas",
                    schemas,
                    "securitySchemes",
                    object(
                        "bearerAuth",
                        object("type", "http", "scheme", "bearer"),
                        "cookieAuth",
                        object("type", "apiKey", "in", "cookie", "name", "authenticationToken"))));
            JsonObject specification = JSON.toJsonTree(document)
                .getAsJsonObject();
            OpenApiExamples.completeExamples(specification);
            Path parent = output.toAbsolutePath()
                .getParent();
            Files.createDirectories(parent);
            Files.writeString(output, JSON.toJson(specification) + "\n", StandardCharsets.UTF_8);
            return true;
        } catch (IllegalArgumentException | IOException exception) {
            reporter.print(Diagnostic.Kind.ERROR, exception.getMessage());
            return false;
        }
    }

    private Map<String, Object> operation(TypeElement endpoint, String routePath, AnnotationMirror route) {
        DocCommentTree comment = environment.getDocTrees()
            .getDocCommentTree(endpoint);
        if (comment == null) throw problem(endpoint, "Missing endpoint Javadoc and @response declarations");
        Map<String, Object> responses = new TreeMap<>();
        Map<String, JsonElement> responseExamples = new TreeMap<>();
        Map<String, String> pathDescriptions = new TreeMap<>();
        for (DocTree tag : comment.getBlockTags()) {
            if (!(tag instanceof UnknownBlockTagTree block)) continue;
            if (block.getTagName()
                .equals("pathParam")) {
                String[] parameter = text(block.getContent()).split("\\s+", 2);
                if (parameter.length != 2 || parameter[1].isBlank()) {
                    throw problem(endpoint, "Expected @pathParam <name> <description>");
                }
                if (pathDescriptions.putIfAbsent(parameter[0], parameter[1]) != null) {
                    throw problem(endpoint, "Duplicate @pathParam: " + parameter[0]);
                }
                continue;
            }
            if (block.getTagName()
                .equals("responseExample")) {
                String[] example = text(block.getContent()).split("\\s+", 2);
                if (example.length != 2 || !example[0].matches("[1-5][0-9]{2}")) {
                    throw problem(endpoint, "Expected @responseExample <status> <JSON>");
                }
                JsonElement body;
                try {
                    body = JSON.fromJson(example[1], JsonElement.class);
                } catch (JsonParseException exception) {
                    throw problem(endpoint, "Invalid @responseExample JSON: " + exception.getMessage());
                }
                if (responseExamples.putIfAbsent(example[0], body) != null) {
                    throw problem(endpoint, "Duplicate @responseExample status: " + example[0]);
                }
                continue;
            }
            if (!block.getTagName()
                .equals("response")) {
                throw problem(endpoint, "Unsupported Javadoc tag: @" + block.getTagName());
            }
            List<? extends DocTree> content = block.getContent();
            if (content.isEmpty() || content.get(0)
                .getKind() != DocTree.Kind.TEXT) {
                throw problem(endpoint, "Malformed @response: expected an HTTP status");
            }
            String initial = content.get(0)
                .toString()
                .stripLeading();
            if (!initial.matches("(?s)[1-5][0-9]{2}(?:\\s.*)?")) {
                throw problem(endpoint, "Malformed @response HTTP status: " + initial);
            }
            String status = initial.substring(0, 3);
            StringBuilder description = new StringBuilder(
                initial.substring(3)
                    .strip());
            Map<String, Object> response = object();
            boolean hasSchema = false;
            for (int index = 1; index < content.size(); index++) {
                DocTree part = content.get(index);
                if (part instanceof LinkTree link) {
                    if (hasSchema || !description.isEmpty()) {
                        throw problem(endpoint, "Malformed @response: schema link must directly follow status");
                    }
                    DocTreePath path = DocTreePath.getPath(
                        environment.getDocTrees()
                            .getPath(endpoint),
                        comment,
                        link.getReference());
                    Element linked = environment.getDocTrees()
                        .getElement(path);
                    if (!(linked instanceof TypeElement type)) {
                        throw problem(endpoint, "Unresolved response type: " + link.getReference());
                    }
                    if (status.equals("204") || status.equals("304") || status.startsWith("1")) {
                        throw problem(endpoint, "HTTP " + status + " cannot declare a response body");
                    }
                    response.put("content", object("application/json", object("schema", schema(type.asType()))));
                    hasSchema = true;
                } else {
                    if (part.getKind() == DocTree.Kind.ERRONEOUS) throw problem(endpoint, "Malformed @response tag");
                    description.append(part);
                }
            }
            String responseDescription = description.toString()
                .strip();
            response.put("description", responseDescription.isEmpty() ? "HTTP " + status : responseDescription);
            if (responses.putIfAbsent(status, response) != null) {
                throw problem(endpoint, "Duplicate @response status: " + status);
            }
        }
        if (responses.isEmpty()) throw problem(endpoint, "Missing @response declarations");
        JsonObject documentedResponses = JSON.toJsonTree(responses)
            .getAsJsonObject();
        for (Map.Entry<String, JsonElement> example : responseExamples.entrySet()) {
            JsonObject response = documentedResponses.getAsJsonObject(example.getKey());
            if (response == null || !response.has("content")) {
                throw problem(endpoint, "@responseExample requires a declared response body: " + example.getKey());
            }
            response.getAsJsonObject("content")
                .getAsJsonObject("application/json")
                .add("example", example.getValue());
        }
        String endpointName = shortName(endpoint);
        Map<String, Object> operation = object(
            "operationId",
            Character.toLowerCase(endpointName.charAt(0)) + endpointName.substring(1),
            "summary",
            text(comment.getFirstSentence()),
            "description",
            text(comment.getBody()),
            "responses",
            documentedResponses);
        addInputs(endpoint, routePath, pathDescriptions, operation);
        if (Boolean.FALSE.equals(annotationValue(route, "authenticated"))) operation.put("security", List.of());
        return operation;
    }

    private void addInputs(TypeElement endpoint, String path, Map<String, String> descriptions,
        Map<String, Object> operation) {
        DeclaredType endpointType = (DeclaredType) endpoint.asType();
        Set<String> placeholders = new LinkedHashSet<>();
        for (String segment : path.split("/", -1)) {
            if (!segment.contains("{") && !segment.contains("}")) continue;
            if (!segment.matches("\\{[a-zA-Z][a-zA-Z0-9_]*}")) {
                throw problem(endpoint, "Malformed path placeholder: " + segment);
            }
            String name = segment.substring(1, segment.length() - 1);
            if (!placeholders.add(name)) throw problem(endpoint, "Duplicate path placeholder: " + name);
        }
        if (!placeholders.equals(descriptions.keySet())) {
            throw problem(endpoint, "@pathParam declarations must match route placeholders: " + placeholders);
        }
        Map<String, Element> fields = new TreeMap<>();
        Element body = null;
        TypeElement current = endpoint;
        while (current != null) {
            for (Element field : current.getEnclosedElements()) {
                if (field.getKind() != ElementKind.FIELD) continue;
                AnnotationMirror parameter = annotation(field, PATH_PARAM);
                if (parameter != null) {
                    String name = annotationValue(parameter, "value").toString();
                    if (placeholders.contains(name) && fields.putIfAbsent(name, field) != null) {
                        throw problem(endpoint, "Duplicate @PathParam field: " + name);
                    }
                }
                if (annotation(field, BODY) != null) {
                    if (body != null) throw problem(endpoint, "Multiple @Body fields");
                    body = field;
                }
            }
            TypeMirror parent = current.getSuperclass();
            current = parent instanceof DeclaredType declared ? (TypeElement) declared.asElement() : null;
        }
        List<Map<String, Object>> parameters = new ArrayList<>();
        for (String name : placeholders) {
            Element field = fields.get(name);
            if (field == null) throw problem(endpoint, "Missing @PathParam field: " + name);
            if (field.getModifiers()
                .contains(Modifier.STATIC)) throw problem(field, "Static @PathParam field");
            Map<String, Object> value = schema(
                environment.getTypeUtils()
                    .asMemberOf(endpointType, field),
                true);
            if (!(value.get("type") instanceof String scalarType) || !Set.of("string", "integer", "number", "boolean")
                .contains(scalarType)) {
                throw problem(field, "Unsupported path parameter type");
            }
            parameters.add(
                object(
                    "name",
                    name,
                    "in",
                    "path",
                    "required",
                    true,
                    "description",
                    descriptions.get(name),
                    "schema",
                    value));
        }
        if (!parameters.isEmpty()) operation.put("parameters", parameters);
        if (body != null) {
            if (body.getModifiers()
                .contains(Modifier.STATIC)) throw problem(body, "Static @Body field");
            TypeMirror type = environment.getTypeUtils()
                .asMemberOf(endpointType, body);
            Map<String, Object> value = schema(type, true);
            if (!value.containsKey("$ref")) throw problem(body, "@Body requires a concrete input DTO");
            Map<String, Object> request = object(
                "required",
                true,
                "content",
                object("application/json", object("schema", value)));
            String description = description(body);
            if (!description.isEmpty()) request.put("description", description);
            operation.put("requestBody", request);
        }
    }

    private Map<String, Object> schema(TypeMirror type) {
        return schema(type, false);
    }

    private Map<String, Object> schema(TypeMirror type, boolean input) {
        Map<String, Object> result = nonNullableSchema(type, input);
        if ("null".equals(result.get("type"))) return result;
        if (!input && type.getAnnotationMirrors()
            .stream()
            .anyMatch(
                annotation -> annotation.getAnnotationType()
                    .toString()
                    .equals("org.jetbrains.annotations.Nullable"))) {
            return object("anyOf", List.of(result, object("type", "null")));
        }
        return result;
    }

    private Map<String, Object> nonNullableSchema(TypeMirror type, boolean input) {
        if (type.getKind() == TypeKind.BOOLEAN) return object("type", "boolean");
        if (Set.of(TypeKind.BYTE, TypeKind.SHORT, TypeKind.INT, TypeKind.LONG)
            .contains(type.getKind())) {
            return object("type", "integer");
        }
        if (type.getKind() == TypeKind.FLOAT || type.getKind() == TypeKind.DOUBLE) return object("type", "number");
        if (type.getKind() == TypeKind.CHAR) return object("type", "string", "minLength", 1, "maxLength", 1);
        if (type instanceof ArrayType array)
            return object("type", "array", "items", schema(array.getComponentType(), input));
        if (!(type instanceof DeclaredType declared))
            throw new IllegalArgumentException("Unsupported schema type: " + type);
        TypeElement element = (TypeElement) declared.asElement();
        String name = element.getQualifiedName()
            .toString();
        switch (name) {
            case "java.lang.String", STABLE_KEY:
                return object("type", "string");
            case "java.util.UUID":
                return object("type", "string", "format", "uuid");
            case "java.lang.Void":
                if (input) throw problem(element, "Null-only input is unsupported");
                return object("type", "null");
            case "java.lang.Boolean":
                return object("type", "boolean");
            case "java.lang.Character":
                return object("type", "string", "minLength", 1, "maxLength", 1);
            case "java.lang.Byte", "java.lang.Short", "java.lang.Integer", "java.lang.Long":
                return object("type", "integer");
            case "java.lang.Float", "java.lang.Double":
                return object("type", "number");
            case "java.util.List", "java.util.Set", "java.util.Collection", "java.util.ArrayList", "java.util.HashSet":
                if (declared.getTypeArguments()
                    .size() != 1) throw problem(element, "Raw collection schema is unsupported");
                return object(
                    "type",
                    "array",
                    "items",
                    schema(
                        declared.getTypeArguments()
                            .get(0),
                        input));
            case "java.util.Map":
                if (declared.getTypeArguments()
                    .size() != 2) throw problem(element, "Raw map schema is unsupported");
                TypeMirror key = declared.getTypeArguments()
                    .get(0);
                if (!(key instanceof DeclaredType keyType) || !Set.of("java.lang.String", "java.util.UUID", STABLE_KEY)
                    .contains(
                        ((TypeElement) keyType.asElement()).getQualifiedName()
                            .toString())) {
                    throw problem(element, "Unsupported map key: " + key);
                }
                return object(
                    "type",
                    "object",
                    "propertyNames",
                    nonNullableSchema(key, input),
                    "additionalProperties",
                    schema(
                        declared.getTypeArguments()
                            .get(1),
                        input));
            default:
                break;
        }
        if (annotation(element, "com.google.gson.annotations.JsonAdapter") != null) {
            throw problem(element, "Unsupported custom Gson adapter");
        }
        if (element.getKind() == ElementKind.ENUM) {
            List<String> values = element.getEnclosedElements()
                .stream()
                .filter(member -> member.getKind() == ElementKind.ENUM_CONSTANT)
                .map(this::wireName)
                .toList();
            return object("type", "string", "enum", values);
        }
        if (name.startsWith("java.") || element.getKind()
            .isInterface()
            || !declared.getTypeArguments()
                .isEmpty()
            || !element.getTypeParameters()
                .isEmpty()
            || element.getModifiers()
                .contains(Modifier.ABSTRACT)
            || (element.getNestingKind()
                .isNested()
                && !element.getModifiers()
                    .contains(Modifier.STATIC))) {
            throw problem(element, "Unsupported schema type: " + type);
        }
        name = shortName(element) + (input ? "Input" : "");
        String qualifiedName = element.getQualifiedName()
            .toString();
        String previousType = schemaTypes.putIfAbsent(name, qualifiedName);
        if (previousType != null && !previousType.equals(qualifiedName)) {
            throw problem(
                element,
                "Ambiguous schema name: " + name + " (" + previousType + " and " + qualifiedName + ")");
        }
        Map<String, Object> reference = object("$ref", "#/components/schemas/" + name);
        if (schemas.containsKey(name)) return reference;
        Map<String, Object> properties = new TreeMap<>();
        schemas.put(name, object("type", "object", "properties", properties));
        List<String> required = new ArrayList<>();
        collectFields(declared, properties, required, input);
        if (properties.isEmpty()) throw problem(element, "Unsupported empty object schema");
        Map<String, Object> definition = object(
            "type",
            "object",
            "properties",
            properties,
            "required",
            required.stream()
                .sorted()
                .toList());
        if (input) definition.put("additionalProperties", false);
        String description = description(element);
        if (!description.isEmpty()) definition.put("description", description);
        schemas.put(name, definition);
        return reference;
    }

    private void collectFields(DeclaredType declared, Map<String, Object> properties, List<String> required,
        boolean input) {
        TypeElement type = (TypeElement) declared.asElement();
        Map<String, String> componentDescriptions = new TreeMap<>();
        Map<String, JsonObject> fieldSchemas = new TreeMap<>();
        DocCommentTree typeComment = environment.getDocTrees()
            .getDocCommentTree(type);
        if (type.getKind() == ElementKind.RECORD && typeComment != null) {
            for (DocTree tag : typeComment.getBlockTags()) {
                if (tag instanceof ParamTree parameter && !parameter.isTypeParameter()) {
                    componentDescriptions.put(
                        parameter.getName()
                            .toString(),
                        text(parameter.getDescription()));
                }
            }
        }
        for (Element field : type.getEnclosedElements()) {
            if (field.getKind() != ElementKind.FIELD || field.getModifiers()
                .contains(Modifier.STATIC)
                || field.getModifiers()
                    .contains(Modifier.TRANSIENT))
                continue;
            if (annotation(field, "com.google.gson.annotations.JsonAdapter") != null) {
                throw problem(field, "Unsupported custom Gson adapter");
            }
            Map<String, Object> value = schema(
                environment.getTypeUtils()
                    .asMemberOf(declared, field),
                input);
            if (!input && annotation(field, "org.jetbrains.annotations.Nullable") != null
                && !value.containsKey("anyOf")
                && !"null".equals(value.get("type"))) {
                value = object("anyOf", List.of(value, object("type", "null")));
            }
            String description = description(field);
            if (description.isEmpty()) description = componentDescriptions.getOrDefault(
                field.getSimpleName()
                    .toString(),
                "");
            if (!description.isEmpty()) value.put("description", description);
            JsonObject propertySchema = JSON.toJsonTree(value)
                .getAsJsonObject();
            for (UnknownBlockTagTree example : exampleTags(
                environment.getDocTrees()
                    .getDocCommentTree(field))) {
                addExample(field, propertySchema, example.getTagName(), text(example.getContent()));
            }
            fieldSchemas.put(
                field.getSimpleName()
                    .toString(),
                propertySchema);
            String name = wireName(field);
            if (!input || annotation(field, OPTIONAL_INPUT) == null) required.add(name);
            if (properties.putIfAbsent(name, propertySchema) != null)
                throw problem(field, "Duplicate serialized field: " + name);
        }
        if (type.getKind() == ElementKind.RECORD) {
            for (UnknownBlockTagTree example : exampleTags(typeComment)) {
                String[] declaration = text(example.getContent()).split("\\s+", 2);
                if (declaration.length != 2 || !fieldSchemas.containsKey(declaration[0])) {
                    throw problem(type, "Expected @" + example.getTagName() + " <record component> <value>");
                }
                addExample(type, fieldSchemas.get(declaration[0]), example.getTagName(), declaration[1]);
            }
        }
        TypeMirror parent = type.getSuperclass();
        if (parent instanceof DeclaredType superclass && !((TypeElement) superclass.asElement()).getQualifiedName()
            .contentEquals("java.lang.Object")
            && !((TypeElement) superclass.asElement()).getQualifiedName()
                .contentEquals("java.lang.Record")) {
            if (!superclass.getTypeArguments()
                .isEmpty()) throw problem(type, "Generic superclass schemas are unsupported");
            collectFields(superclass, properties, required, input);
        }
    }

    private static String shortName(TypeElement type) {
        StringBuilder name = new StringBuilder(type.getSimpleName());
        Element owner = type.getEnclosingElement();
        while (owner instanceof TypeElement enclosingType) {
            name.insert(0, enclosingType.getSimpleName());
            owner = enclosingType.getEnclosingElement();
        }
        return name.toString();
    }

    private static List<UnknownBlockTagTree> exampleTags(DocCommentTree comment) {
        if (comment == null) return List.of();
        return comment.getBlockTags()
            .stream()
            .filter(tag -> tag instanceof UnknownBlockTagTree)
            .map(tag -> (UnknownBlockTagTree) tag)
            .filter(
                tag -> tag.getTagName()
                    .equals("example")
                    || tag.getTagName()
                        .equals("keyExample"))
            .toList();
    }

    private static void addExample(Element owner, JsonObject schema, String tag, String value) {
        try {
            OpenApiExamples.addHint(schema, tag, value);
        } catch (IllegalArgumentException | JsonParseException exception) {
            throw problem(owner, "@" + tag + ": " + exception.getMessage());
        }
    }

    private String description(Element element) {
        DocCommentTree comment = environment.getDocTrees()
            .getDocCommentTree(element);
        return comment == null ? "" : text(comment.getFullBody());
    }

    private String wireName(Element element) {
        AnnotationMirror serializedName = annotation(element, "com.google.gson.annotations.SerializedName");
        return serializedName == null ? element.getSimpleName()
            .toString() : annotationValue(serializedName, "value").toString();
    }

    private static AnnotationMirror annotation(Element element, String name) {
        return element.getAnnotationMirrors()
            .stream()
            .filter(
                annotation -> annotation.getAnnotationType()
                    .toString()
                    .equals(name))
            .findFirst()
            .orElse(null);
    }

    private Object annotationValue(AnnotationMirror annotation, String member) {
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : environment.getElementUtils()
            .getElementValuesWithDefaults(annotation)
            .entrySet()) {
            if (entry.getKey()
                .getSimpleName()
                .contentEquals(member))
                return entry.getValue()
                    .getValue();
        }
        throw new IllegalArgumentException("Missing annotation member: " + member);
    }

    private static String text(List<? extends DocTree> content) {
        StringBuilder result = new StringBuilder();
        for (DocTree tree : content) {
            if (tree instanceof LiteralTree literal) {
                if (tree.getKind() == DocTree.Kind.CODE) result.append('`');
                result.append(literal.getBody());
                if (tree.getKind() == DocTree.Kind.CODE) result.append('`');
            } else if (tree instanceof LinkTree link) {
                result.append(
                    link.getLabel()
                        .isEmpty()
                            ? link.getReference()
                                .toString()
                            : text(link.getLabel()));
            } else {
                result.append(tree);
            }
        }
        return result.toString()
            .strip();
    }

    private static IllegalArgumentException problem(Element element, String message) {
        return new IllegalArgumentException(element + ": " + message);
    }

    private static Map<String, Object> object(Object... entries) {
        Map<String, Object> result = new TreeMap<>();
        for (int index = 0; index < entries.length; index += 2) result.put((String) entries[index], entries[index + 1]);
        return result;
    }
}
