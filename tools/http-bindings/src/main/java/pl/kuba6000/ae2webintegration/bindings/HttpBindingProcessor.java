package pl.kuba6000.ae2webintegration.bindings;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;

/** Generates ordinary Java companions using only the standard compiler model. */
@SupportedAnnotationTypes("pl.kuba6000.ae2webintegration.core.http.contract.GenerateHttpBinding")
@SupportedSourceVersion(SourceVersion.RELEASE_17)
@SupportedOptions("httpBindings.catalog")
public final class HttpBindingProcessor extends AbstractProcessor {

    private static final String CONTRACT = "pl.kuba6000.ae2webintegration.core.http.contract.";
    private static final String HTTP = "pl.kuba6000.ae2webintegration.core.http.";
    private final Set<String> emitted = new HashSet<>();

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        TypeElement marker = processingEnv.getElementUtils()
            .getTypeElement(CONTRACT + "GenerateHttpBinding");
        if (marker == null || round.processingOver()) return false;
        List<TypeElement> endpoints = new ArrayList<>();
        for (Element element : round.getElementsAnnotatedWith(marker)) endpoints.add((TypeElement) element);
        if (endpoints.isEmpty()) return false;
        endpoints.sort(
            Comparator.comparing(
                type -> type.getQualifiedName()
                    .toString()));
        try {
            for (TypeElement endpoint : endpoints) {
                if (annotation(endpoint, "Endpoint") == null) {
                    error(endpoint, "Generated binding requires @Endpoint");
                    return true;
                }
                for (TypeElement owner : hierarchy(endpoint)) emitOwner(owner);
            }
            emitCatalog(endpoints);
        } catch (IOException exception) {
            error(endpoints.get(0), "Cannot generate HTTP bindings: " + exception.getMessage());
        }
        return true;
    }

    private void emitOwner(TypeElement owner) throws IOException {
        List<VariableElement> fields = inputFields(owner);
        if (fields.isEmpty()) return;
        String name = companion(owner);
        if (!emitted.add(name) || processingEnv.getElementUtils()
            .getTypeElement(name) != null) return;
        StringBuilder code = header(owner, name);
        Map<TypeElement, String> shapes = new LinkedHashMap<>();
        code.append("  public static void bind(")
            .append(owner.getQualifiedName())
            .append(" target, ")
            .append(HTTP)
            .append("InputValues values) {\n");
        int index = 0;
        for (VariableElement field : fields) {
            if (field.getModifiers()
                .contains(Modifier.PRIVATE)
                || field.getModifiers()
                    .contains(Modifier.STATIC)
                || field.getModifiers()
                    .contains(Modifier.FINAL)) {
                error(
                    field,
                    "HTTP input must be mutable and accessible to its same-package companion: "
                        + field.getSimpleName());
                continue;
            }
            AnnotationMirror query = annotation(field, "QueryParam");
            AnnotationMirror path = annotation(field, "PathParam");
            if (query == null && path == null) {
                TypeElement input = (TypeElement) processingEnv.getTypeUtils()
                    .asElement(field.asType());
                if (input == null) {
                    error(field, "HTTP body requires an object type");
                    continue;
                }
                String validator = shape(input, shapes);
                code.append("    target.")
                    .append(field.getSimpleName())
                    .append(" = values.body(")
                    .append(input.getQualifiedName())
                    .append(".class, ")
                    .append(name)
                    .append("::")
                    .append(validator)
                    .append(");\n");
                continue;
            }
            String inputName = value(query != null ? query : path, "value");
            String local = "value" + index++;
            String conversion = conversion(field, local);
            if (conversion == null) continue;
            if (query != null) {
                code.append("    String ")
                    .append(local)
                    .append(" = values.query(")
                    .append(quote(inputName))
                    .append(");\n")
                    .append("    if (")
                    .append(local)
                    .append(" != null) target.")
                    .append(field.getSimpleName())
                    .append(" = ")
                    .append(conversion)
                    .append(";\n");
            } else {
                code.append("    if (values.hasPath(")
                    .append(quote(inputName))
                    .append(")) {\n")
                    .append("      String ")
                    .append(local)
                    .append(" = values.path(")
                    .append(quote(inputName))
                    .append(");\n")
                    .append("      target.")
                    .append(field.getSimpleName())
                    .append(" = ")
                    .append(conversion)
                    .append(";\n    }\n");
            }
        }
        code.append("  }\n");
        List<TypeElement> pending = new ArrayList<>(shapes.keySet());
        for (int shapeIndex = 0; shapeIndex < pending.size(); shapeIndex++) {
            emitShape(pending.get(shapeIndex), shapes, code);
            pending = new ArrayList<>(shapes.keySet());
        }
        code.append("}\n");
        write(name, code.toString(), owner);
    }

    private void emitCatalog(List<TypeElement> endpoints) throws IOException {
        String name = processingEnv.getOptions()
            .getOrDefault("httpBindings.catalog", HTTP + "GeneratedHttpBindings");
        String packageName = name.substring(0, name.lastIndexOf('.'));
        String simple = name.substring(name.lastIndexOf('.') + 1);
        StringBuilder code = new StringBuilder("package ").append(packageName)
            .append(";\n\npublic final class ")
            .append(simple)
            .append(" {\n  private ")
            .append(simple)
            .append("() {}\n");
        for (int index = 0; index < endpoints.size(); index++) {
            TypeElement endpoint = endpoints.get(index);
            AnnotationMirror route = annotation(endpoint, "Endpoint");
            code.append("  private static final ")
                .append(HTTP)
                .append("HttpBinding BINDING_")
                .append(index)
                .append(" = new ")
                .append(HTTP)
                .append("HttpBinding(\n    ")
                .append(CONTRACT)
                .append("HttpMethod.")
                .append(value(route, "method"))
                .append(", ")
                .append(quote(value(route, "path")))
                .append(", ")
                .append(CONTRACT)
                .append("Authentication.")
                .append(value(route, "authentication"))
                .append(",\n    ");
            TypeElement requestType = processingEnv.getElementUtils()
                .getTypeElement("pl.kuba6000.ae2webintegration.core.ae2request.IRequest");
            boolean request = processingEnv.getTypeUtils()
                .isSubtype(endpoint.asType(), requestType.asType());
            code.append(request ? endpoint.getQualifiedName() + "::new" : "null")
                .append(", (request, values) -> {\n");
            for (TypeElement owner : hierarchy(endpoint)) {
                if (!inputFields(owner).isEmpty()) code.append("      ")
                    .append(companion(owner))
                    .append(".bind((")
                    .append(endpoint.getQualifiedName())
                    .append(") request, values);\n");
            }
            long bodies = hierarchy(endpoint).stream()
                .flatMap(type -> inputFields(type).stream())
                .filter(field -> annotation(field, "Body") != null)
                .count();
            if (bodies > 1) error(endpoint, "Only one body field is allowed");
            if (bodies == 0) code.append("      values.noBody();\n");
            code.append("    });\n");
        }
        code.append("  public static @org.jetbrains.annotations.Nullable ")
            .append(HTTP)
            .append("HttpBinding get(Class<?> type) {\n");
        for (int index = 0; index < endpoints.size(); index++) code.append("    if (type == ")
            .append(
                endpoints.get(index)
                    .getQualifiedName())
            .append(".class) return BINDING_")
            .append(index)
            .append(";\n");
        code.append("    return null;\n  }\n}\n");
        write(name, code.toString(), endpoints.toArray(new Element[0]));
    }

    private String shape(TypeElement type, Map<TypeElement, String> shapes) {
        return shapes.computeIfAbsent(type, ignored -> "validate" + shapes.size());
    }

    private void emitShape(TypeElement type, Map<TypeElement, String> shapes, StringBuilder code) {
        List<VariableElement> fields = ElementFilter.fieldsIn(type.getEnclosedElements())
            .stream()
            .filter(
                field -> !field.getModifiers()
                    .contains(Modifier.STATIC)
                    && !field.getModifiers()
                        .contains(Modifier.TRANSIENT))
            .toList();
        code.append("  private static void ")
            .append(shapes.get(type))
            .append("(com.google.gson.JsonObject body) {\n")
            .append("    for (java.util.Map.Entry<String, com.google.gson.JsonElement> member : body.entrySet()) {\n")
            .append("      switch (member.getKey()) {\n");
        for (VariableElement field : fields) code.append("        case ")
            .append(
                quote(
                    field.getSimpleName()
                        .toString()))
            .append(":\n");
        if (!fields.isEmpty()) code.append("          break;\n");
        code.append(
            "        default: throw new IllegalArgumentException(\"Unknown JSON member: \" + member.getKey());\n      }\n    }\n");
        for (VariableElement field : fields) {
            String fieldName = quote(
                field.getSimpleName()
                    .toString());
            String value = "body.get(" + fieldName + ")";
            String fieldType = typeName(field.asType());
            boolean optional = annotation(field, "OptionalInput") != null;
            if (optional) code.append("    if (body.has(")
                .append(fieldName)
                .append(")) {\n");
            String call = switch (fieldType) {
                case "java.lang.String" -> HTTP + "InputValues.string(" + value + ", " + fieldName + ")";
                case "pl.kuba6000.ae2webintegration.core.identity.StableKey" -> HTTP + "InputValues.key("
                    + value
                    + ", "
                    + fieldName
                    + ")";
                case "boolean", "java.lang.Boolean" -> HTTP + "InputValues.bool(" + value + ", " + fieldName + ")";
                case "int", "java.lang.Integer" -> HTTP + "InputValues.integer(" + value + ", " + fieldName + ", true)";
                case "long", "java.lang.Long" -> HTTP + "InputValues.integer(" + value + ", " + fieldName + ", false)";
                default -> {
                    Element nested = processingEnv.getTypeUtils()
                        .asElement(field.asType());
                    if (!(nested instanceof TypeElement nestedType) || fieldType.startsWith("java.")) {
                        error(field, "Unsupported JSON member type: " + fieldType);
                        yield "";
                    }
                    yield shape(nestedType, shapes) + "("
                        + HTTP
                        + "InputValues.object("
                        + value
                        + ", "
                        + fieldName
                        + "))";
                }
            };
            code.append("      ")
                .append(call)
                .append(";\n");
            if (optional) code.append("    }\n");
        }
        code.append("  }\n");
    }

    private String conversion(VariableElement field, String value) {
        String type = typeName(field.asType());
        return switch (type) {
            case "java.lang.String" -> value;
            case "boolean" -> HTTP + "InputValues.booleanValue(" + value + ")";
            case "int", "java.lang.Integer" -> "Integer.parseInt(" + value + ")";
            case "long", "java.lang.Long" -> "Long.parseLong(" + value + ")";
            case "pl.kuba6000.ae2webintegration.core.identity.StableKey" -> type + ".parse(" + value + ")";
            default -> {
                error(field, "Unsupported HTTP parameter type: " + type);
                yield null;
            }
        };
    }

    private String typeName(TypeMirror mirror) {
        Element element = processingEnv.getTypeUtils()
            .asElement(mirror);
        return element instanceof TypeElement type ? type.getQualifiedName()
            .toString() : mirror.toString();
    }

    private List<TypeElement> hierarchy(TypeElement endpoint) {
        List<TypeElement> hierarchy = new ArrayList<>();
        for (TypeElement type = endpoint; type != null;) {
            hierarchy.add(type);
            type = type.getSuperclass()
                .getKind() == TypeKind.NONE ? null
                    : (TypeElement) processingEnv.getTypeUtils()
                        .asElement(type.getSuperclass());
        }
        return hierarchy;
    }

    private List<VariableElement> inputFields(TypeElement type) {
        List<VariableElement> fields = new ArrayList<>();
        for (VariableElement field : ElementFilter.fieldsIn(type.getEnclosedElements())) {
            if (annotation(field, "QueryParam") != null || annotation(field, "PathParam") != null
                || annotation(field, "Body") != null) fields.add(field);
        }
        return fields;
    }

    private AnnotationMirror annotation(Element element, String name) {
        for (AnnotationMirror annotation : element.getAnnotationMirrors()) {
            if (annotation.getAnnotationType()
                .toString()
                .equals(CONTRACT + name)) return annotation;
        }
        return null;
    }

    private String value(AnnotationMirror annotation, String name) {
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : processingEnv.getElementUtils()
            .getElementValuesWithDefaults(annotation)
            .entrySet()) {
            if (entry.getKey()
                .getSimpleName()
                .contentEquals(name))
                return entry.getValue()
                    .getValue()
                    .toString();
        }
        throw new IllegalArgumentException("Missing annotation value: " + name);
    }

    private String companion(TypeElement owner) {
        String packageName = processingEnv.getElementUtils()
            .getPackageOf(owner)
            .getQualifiedName()
            .toString();
        return packageName + "."
            + owner.getQualifiedName()
                .toString()
                .substring(packageName.length() + 1)
                .replace('.', '_')
            + "_HttpBinding";
    }

    private StringBuilder header(TypeElement owner, String name) {
        String simple = name.substring(name.lastIndexOf('.') + 1);
        return new StringBuilder("package ").append(
            processingEnv.getElementUtils()
                .getPackageOf(owner)
                .getQualifiedName())
            .append(";\n\npublic final class ")
            .append(simple)
            .append(" {\n  private ")
            .append(simple)
            .append("() {}\n");
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r") + "\"";
    }

    private void write(String name, String code, Element... origins) throws IOException {
        try (Writer writer = processingEnv.getFiler()
            .createSourceFile(name, origins)
            .openWriter()) {
            writer.write(code);
        }
    }

    private void error(Element element, String message) {
        processingEnv.getMessager()
            .printMessage(Diagnostic.Kind.ERROR, message, element);
    }
}
