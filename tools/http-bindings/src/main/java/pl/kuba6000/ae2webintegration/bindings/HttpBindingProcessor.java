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
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.WildcardTypeName;

/** Generates ordinary Java companions using only the standard compiler model. */
@SupportedAnnotationTypes({ "pl.kuba6000.ae2webintegration.core.http.contract.Endpoint",
    "pl.kuba6000.ae2webintegration.core.json.JsonProjection" })
@SupportedSourceVersion(SourceVersion.RELEASE_17)
@SupportedOptions("bindings.catalog")
public final class HttpBindingProcessor extends AbstractProcessor {

    private static final String CONTRACT = "pl.kuba6000.ae2webintegration.core.http.contract.";
    private static final String BINDINGS = "pl.kuba6000.ae2webintegration.core.binding";
    private static final String JSON_PROJECTION = "pl.kuba6000.ae2webintegration.core.json.JsonProjection";
    private static final String HTTP = "pl.kuba6000.ae2webintegration.core.http";
    private static final ClassName INPUT_VALUES = ClassName.get(HTTP, "InputValues");
    private static final ClassName HTTP_BINDING = ClassName.get(HTTP, "HttpBinding");
    private static final ClassName STABLE_KEY = ClassName
        .get("pl.kuba6000.ae2webintegration.core.identity", "StableKey");
    private static final ClassName JSON_OBJECT = ClassName.get("com.google.gson", "JsonObject");
    private static final ClassName JSON_ELEMENT = ClassName.get("com.google.gson", "JsonElement");
    private static final ClassName TYPE_ADAPTER = ClassName.get("com.google.gson", "TypeAdapter");
    private final Set<String> emitted = new HashSet<>();

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        if (round.processingOver()) return false;
        TypeElement marker = processingEnv.getElementUtils()
            .getTypeElement(CONTRACT + "Endpoint");
        TypeElement shapeMarker = processingEnv.getElementUtils()
            .getTypeElement(JSON_PROJECTION);
        if (marker == null || shapeMarker == null) {
            processingEnv.getMessager()
                .printMessage(
                    Diagnostic.Kind.ERROR,
                    "Binding processor requires Endpoint and JsonProjection on the compilation classpath");
            return false;
        }
        List<TypeElement> endpoints = new ArrayList<>();
        for (Element element : round.getElementsAnnotatedWith(marker)) endpoints.add((TypeElement) element);
        List<TypeElement> projections = new ArrayList<>();
        for (Element element : round.getElementsAnnotatedWith(shapeMarker)) projections.add((TypeElement) element);
        if (endpoints.isEmpty() && projections.isEmpty()) return false;
        endpoints.sort(
            Comparator.comparing(
                type -> type.getQualifiedName()
                    .toString()));
        try {
            for (TypeElement endpoint : endpoints) {
                for (TypeElement owner : hierarchy(endpoint)) emitOwner(owner);
            }
            List<TypeElement> validProjections = new ArrayList<>();
            for (TypeElement projection : projections) {
                if (emitProjection(projection)) validProjections.add(projection);
            }
            emitCatalog(endpoints, validProjections);
        } catch (IOException exception) {
            error(
                endpoints.isEmpty() ? projections.get(0) : endpoints.get(0),
                "Cannot generate HTTP bindings: " + exception.getMessage());
        }
        return true;
    }

    private void emitOwner(TypeElement owner) throws IOException {
        List<VariableElement> fields = inputFields(owner);
        boolean endpoint = annotation(owner, "Endpoint") != null;
        if (fields.isEmpty() && !endpoint) return;
        ClassName name = companion(owner);
        if (!emitted.add(name.canonicalName()) || processingEnv.getElementUtils()
            .getTypeElement(name.canonicalName()) != null) return;
        TypeSpec.Builder companion = TypeSpec.classBuilder(name)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addOriginatingElement(owner)
            .addMethod(
                MethodSpec.constructorBuilder()
                    .addModifiers(Modifier.PRIVATE)
                    .build());
        Map<Shape, String> shapes = new LinkedHashMap<>();
        MethodSpec.Builder bind = MethodSpec.methodBuilder("bind")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter(ClassName.get(owner), "target")
            .addParameter(INPUT_VALUES, "values");
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
            AnnotationMirror body = annotation(field, "Body");
            int transports = (query == null ? 0 : 1) + (path == null ? 0 : 1) + (body == null ? 0 : 1);
            if (transports != 1) {
                error(field, "HTTP input must have exactly one transport annotation");
                continue;
            }
            if (query == null && path == null) {
                TypeElement input = (TypeElement) processingEnv.getTypeUtils()
                    .asElement(field.asType());
                if (input == null) {
                    error(field, "HTTP body requires an object type");
                    continue;
                }
                bind.addStatement(
                    "target.$N = values.body($T.class, $T::$N)",
                    field.getSimpleName(),
                    ClassName.get(input),
                    name,
                    shape(input, null, shapes));
                continue;
            }
            String inputName = value(query != null ? query : path, "value");
            String local = "value" + index++;
            CodeBlock conversion = conversion(field, local);
            if (conversion == null) continue;
            if (query != null) {
                bind.addStatement("$T $N = values.query($S)", String.class, local, inputName)
                    .beginControlFlow("if ($N != null)", local)
                    .addStatement("target.$N = $L", field.getSimpleName(), conversion)
                    .endControlFlow();
            } else {
                bind.beginControlFlow("if (values.hasPath($S))", inputName)
                    .addStatement("$T $N = values.path($S)", String.class, local, inputName)
                    .addStatement("target.$N = $L", field.getSimpleName(), conversion)
                    .endControlFlow();
            }
        }
        companion.addMethod(bind.build());
        if (endpoint) {
            FieldSpec binding = endpointBinding(owner);
            companion.addField(binding)
                .addMethod(
                    MethodSpec.methodBuilder("get")
                        .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                        .addAnnotation(ClassName.get("org.jetbrains.annotations", "Nullable"))
                        .returns(HTTP_BINDING)
                        .addParameter(
                            ParameterizedTypeName
                                .get(ClassName.get(Class.class), WildcardTypeName.subtypeOf(Object.class)),
                            "type")
                        .addStatement("return type == $T.class ? $N : null", ClassName.get(owner), binding)
                        .build());
        }
        List<Shape> pending = new ArrayList<>(shapes.keySet());
        for (int shapeIndex = 0; shapeIndex < pending.size(); shapeIndex++) {
            companion.addMethod(shapeValidator(pending.get(shapeIndex), shapes));
            pending = new ArrayList<>(shapes.keySet());
        }
        JavaFile.builder(name.packageName(), companion.build())
            .indent("    ")
            .skipJavaLangImports(true)
            .build()
            .writeTo(processingEnv.getFiler());
    }

    private FieldSpec endpointBinding(TypeElement endpoint) {
        TypeElement requestType = processingEnv.getElementUtils()
            .getTypeElement("pl.kuba6000.ae2webintegration.core.ae2request.IRequest");
        AnnotationMirror route = annotation(endpoint, "Endpoint");
        ClassName endpointName = ClassName.get(endpoint);
        boolean request = processingEnv.getTypeUtils()
            .isSubtype(endpoint.asType(), requestType.asType());
        CodeBlock factory = request ? CodeBlock.of("$T::new", endpointName) : CodeBlock.of("null");
        CodeBlock.Builder initializer = CodeBlock.builder()
            .add(
                "new $T($T.$L,$W$S,$W$T.$L,$W$L,$W(request, values) -> {\n",
                HTTP_BINDING,
                ClassName.get(HTTP + ".contract", "HttpMethod"),
                value(route, "method"),
                value(route, "path"),
                ClassName.get(HTTP + ".contract", "Authentication"),
                value(route, "authentication"),
                factory)
            .indent();
        List<TypeElement> owners = hierarchy(endpoint);
        for (TypeElement owner : owners) {
            if (!inputFields(owner).isEmpty())
                initializer.addStatement("$T.bind(($T) request, values)", companion(owner), endpointName);
        }
        long bodies = owners.stream()
            .flatMap(type -> inputFields(type).stream())
            .filter(field -> annotation(field, "Body") != null)
            .count();
        if (bodies > 1) error(endpoint, "Only one body field is allowed");
        if (bodies == 0) initializer.addStatement("values.noBody()");
        initializer.unindent()
            .add("})");
        FieldSpec binding = FieldSpec
            .builder(HTTP_BINDING, "BINDING", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .initializer(initializer.build())
            .build();
        return binding;
    }

    private boolean emitProjection(TypeElement wrapper) throws IOException {
        AnnotationMirror projection = annotationNamed(wrapper, JSON_PROJECTION);
        Element represented = processingEnv.getTypeUtils()
            .asElement((TypeMirror) annotationValue(projection, "value"));
        if (!(represented instanceof TypeElement valueType)
            || (valueType.getKind() != ElementKind.CLASS && valueType.getKind() != ElementKind.RECORD)) {
            error(wrapper, "JsonProjection requires a class or record as its represented type");
            return false;
        }
        List<VariableElement> fields = ElementFilter.fieldsIn(wrapper.getEnclosedElements())
            .stream()
            .filter(
                field -> !field.getModifiers()
                    .contains(Modifier.STATIC))
            .toList();
        if (fields.size() != 1 || !wrapper.getTypeParameters()
            .isEmpty()
            || !processingEnv.getTypeUtils()
                .isSameType(
                    fields.get(0)
                        .asType(),
                    valueType.asType())
            || wrapper.getKind() != ElementKind.RECORD) {
            error(
                wrapper,
                "JsonProjection requires one record component matching its represented type and no type parameters");
            return false;
        }
        for (Element owner = wrapper; owner instanceof TypeElement; owner = owner.getEnclosingElement()) {
            if (owner.getModifiers()
                .contains(Modifier.PRIVATE)) {
                error(wrapper, "JsonProjection wrapper must be accessible to its same-package adapter");
                return false;
            }
        }
        TypeElement excluded = (TypeElement) processingEnv.getTypeUtils()
            .asElement((TypeMirror) annotationValue(projection, "excludeFieldsAnnotatedWith"));
        AnnotationMirror retention = annotationNamed(excluded, "java.lang.annotation.Retention");
        if (retention == null || !value(retention, "value").equals("RUNTIME")) {
            error(wrapper, "JsonProjection exclusion annotation requires RUNTIME retention for Gson");
            return false;
        }
        ClassName name = companion(wrapper, "_JsonAdapter");
        ParameterizedTypeName wrapperAdapter = ParameterizedTypeName.get(TYPE_ADAPTER, ClassName.get(wrapper));
        TypeSpec adapter = TypeSpec.anonymousClassBuilder("")
            .superclass(wrapperAdapter)
            .addMethod(
                MethodSpec.methodBuilder("write")
                    .addAnnotation(Override.class)
                    .addModifiers(Modifier.PUBLIC)
                    .addParameter(ClassName.get("com.google.gson.stream", "JsonWriter"), "output")
                    .addParameter(ClassName.get(wrapper), "wrapper")
                    .addException(IOException.class)
                    .addStatement(
                        "delegate.write(output, wrapper.$N())",
                        fields.get(0)
                            .getSimpleName())
                    .build())
            .addMethod(
                MethodSpec.methodBuilder("read")
                    .addAnnotation(Override.class)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(ClassName.get(wrapper))
                    .addParameter(ClassName.get("com.google.gson.stream", "JsonReader"), "input")
                    .addException(IOException.class)
                    .addStatement("return new $T(delegate.read(input))", ClassName.get(wrapper))
                    .build())
            .build();
        TypeSpec companion = TypeSpec.classBuilder(name)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addOriginatingElement(wrapper)
            .addMethod(
                MethodSpec.constructorBuilder()
                    .addModifiers(Modifier.PRIVATE)
                    .build())
            .addMethod(
                MethodSpec.methodBuilder("get")
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                    .addAnnotation(ClassName.get("org.jetbrains.annotations", "Nullable"))
                    .returns(ParameterizedTypeName.get(TYPE_ADAPTER, WildcardTypeName.subtypeOf(Object.class)))
                    .addParameter(
                        ParameterizedTypeName.get(ClassName.get(Class.class), WildcardTypeName.subtypeOf(Object.class)),
                        "type")
                    .addStatement("if (type != $T.class) return null", ClassName.get(wrapper))
                    .addStatement(
                        "$T delegate = $T.projectionAdapter($T.class, $T.class)",
                        ParameterizedTypeName.get(TYPE_ADAPTER, ClassName.get(valueType)),
                        ClassName.get("pl.kuba6000.ae2webintegration.core.utils", "GSONUtils"),
                        ClassName.get(valueType),
                        ClassName.get(excluded))
                    .addStatement("return $L.nullSafe()", adapter)
                    .build())
            .build();
        JavaFile.builder(name.packageName(), companion)
            .indent("    ")
            .skipJavaLangImports(true)
            .build()
            .writeTo(processingEnv.getFiler());
        return true;
    }

    private void emitCatalog(List<TypeElement> endpoints, List<TypeElement> projections) throws IOException {
        String configuredName = processingEnv.getOptions()
            .getOrDefault("bindings.catalog", BINDINGS + ".GeneratedBindings");
        int lastDot = configuredName.lastIndexOf('.');
        ClassName name = ClassName.get(configuredName.substring(0, lastDot), configuredName.substring(lastDot + 1));
        ClassName catalogType = ClassName.get(BINDINGS, "BindingCatalog");
        TypeSpec.Builder catalog = TypeSpec.classBuilder(name)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addSuperinterface(catalogType);
        MethodSpec.Builder lookup = MethodSpec.methodBuilder("get")
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(Override.class)
            .addAnnotation(ClassName.get("org.jetbrains.annotations", "Nullable"))
            .returns(HTTP_BINDING)
            .addParameter(
                ParameterizedTypeName.get(ClassName.get(Class.class), WildcardTypeName.subtypeOf(Object.class)),
                "type")
            .addStatement("$T binding", HTTP_BINDING);
        for (TypeElement endpoint : endpoints) {
            catalog.addOriginatingElement(endpoint);
            lookup.addStatement("binding = $T.get(type)", companion(endpoint))
                .beginControlFlow("if (binding != null)")
                .addStatement("return binding")
                .endControlFlow();
        }
        catalog.addMethod(
            lookup.addStatement("return null")
                .build());
        MethodSpec.Builder adapters = MethodSpec.methodBuilder("adapter")
            .addAnnotation(Override.class)
            .addAnnotation(ClassName.get("org.jetbrains.annotations", "Nullable"))
            .addModifiers(Modifier.PUBLIC)
            .returns(ParameterizedTypeName.get(TYPE_ADAPTER, WildcardTypeName.subtypeOf(Object.class)))
            .addParameter(
                ParameterizedTypeName.get(ClassName.get(Class.class), WildcardTypeName.subtypeOf(Object.class)),
                "type");
        if (!projections.isEmpty()) adapters.addStatement(
            "$T adapter",
            ParameterizedTypeName.get(TYPE_ADAPTER, WildcardTypeName.subtypeOf(Object.class)));
        for (TypeElement projection : projections) {
            catalog.addOriginatingElement(projection);
            adapters.addStatement("adapter = $T.get(type)", companion(projection, "_JsonAdapter"))
                .addStatement("if (adapter != null) return adapter");
        }
        catalog.addMethod(
            adapters.addStatement("return null")
                .build());
        JavaFile.builder(name.packageName(), catalog.build())
            .indent("    ")
            .skipJavaLangImports(true)
            .build()
            .writeTo(processingEnv.getFiler());
        List<TypeElement> origins = new ArrayList<>(endpoints);
        origins.addAll(projections);
        try (Writer writer = processingEnv.getFiler()
            .createResource(
                StandardLocation.CLASS_OUTPUT,
                "",
                "META-INF/services/" + catalogType.canonicalName(),
                origins.toArray(new Element[0]))
            .openWriter()) {
            writer.write(name.canonicalName() + "\n");
        }
    }

    private record Shape(TypeElement type, TypeElement excludedAnnotation) {}

    private String shape(TypeElement type, TypeElement excludedAnnotation, Map<Shape, String> shapes) {
        AnnotationMirror projection = annotationNamed(type, JSON_PROJECTION);
        if (projection != null) {
            Element represented = processingEnv.getTypeUtils()
                .asElement((TypeMirror) annotationValue(projection, "value"));
            if (represented instanceof TypeElement representedType && (representedType.getKind() == ElementKind.CLASS
                || representedType.getKind() == ElementKind.RECORD)) {
                type = representedType;
                excludedAnnotation = (TypeElement) processingEnv.getTypeUtils()
                    .asElement((TypeMirror) annotationValue(projection, "excludeFieldsAnnotatedWith"));
            } else {
                error(type, "JsonProjection requires a class or record as its represented type");
            }
        }
        return shapes.computeIfAbsent(new Shape(type, excludedAnnotation), ignored -> "validate" + shapes.size());
    }

    private MethodSpec shapeValidator(Shape shape, Map<Shape, String> shapes) {
        List<VariableElement> fields = ElementFilter.fieldsIn(
            shape.type()
                .getEnclosedElements())
            .stream()
            .filter(
                field -> !field.getModifiers()
                    .contains(Modifier.STATIC)
                    && !field.getModifiers()
                        .contains(Modifier.TRANSIENT)
                    && !(shape.excludedAnnotation() != null && field.getAnnotationMirrors()
                        .stream()
                        .anyMatch(
                            annotation -> annotation.getAnnotationType()
                                .asElement()
                                .equals(shape.excludedAnnotation()))))
            .toList();
        MethodSpec.Builder validate = MethodSpec.methodBuilder(shapes.get(shape))
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .addParameter(JSON_OBJECT, "body")
            .beginControlFlow(
                "for ($T member : body.entrySet())",
                ParameterizedTypeName.get(ClassName.get(Map.Entry.class), ClassName.get(String.class), JSON_ELEMENT))
            .beginControlFlow("switch (member.getKey())");
        for (VariableElement field : fields) validate.addCode(
            "case $S:\n",
            field.getSimpleName()
                .toString());
        if (!fields.isEmpty()) validate.addCode("$>")
            .addStatement("break")
            .addCode("$<");
        validate.addCode("default:\n$>")
            .addStatement("throw new $T($S + member.getKey())", IllegalArgumentException.class, "Unknown JSON member: ")
            .addCode("$<")
            .endControlFlow()
            .endControlFlow();
        for (VariableElement field : fields) {
            String fieldName = field.getSimpleName()
                .toString();
            CodeBlock value = CodeBlock.of("body.get($S)", fieldName);
            String fieldType = typeName(field.asType());
            boolean optional = annotation(field, "OptionalInput") != null;
            if (optional) validate.beginControlFlow("if (body.has($S))", fieldName);
            CodeBlock call = switch (fieldType) {
                case "java.lang.String" -> CodeBlock.of("$T.string($L, $S)", INPUT_VALUES, value, fieldName);
                case "pl.kuba6000.ae2webintegration.core.identity.StableKey" -> CodeBlock
                    .of("$T.key($L, $S)", INPUT_VALUES, value, fieldName);
                case "boolean", "java.lang.Boolean" -> CodeBlock.of("$T.bool($L, $S)", INPUT_VALUES, value, fieldName);
                case "int", "java.lang.Integer" -> CodeBlock
                    .of("$T.integer($L, $S, true)", INPUT_VALUES, value, fieldName);
                case "long", "java.lang.Long" -> CodeBlock
                    .of("$T.integer($L, $S, false)", INPUT_VALUES, value, fieldName);
                default -> {
                    Element nested = processingEnv.getTypeUtils()
                        .asElement(field.asType());
                    if (!(nested instanceof TypeElement nestedType) || fieldType.startsWith("java.")) {
                        error(field, "Unsupported JSON member type: " + fieldType);
                        yield CodeBlock.of("");
                    }
                    if (nestedType.getKind() == ElementKind.ENUM) {
                        validate.addStatement("$T.string($L, $S)", INPUT_VALUES, value, fieldName);
                        yield CodeBlock.of("$T.valueOf($L.getAsString())", ClassName.get(nestedType), value);
                    }
                    yield CodeBlock.of(
                        "$N($T.object($L, $S))",
                        shape(nestedType, shape.excludedAnnotation(), shapes),
                        INPUT_VALUES,
                        value,
                        fieldName);
                }
            };
            validate.addStatement("$L", call);
            if (optional) validate.endControlFlow();
        }
        return validate.build();
    }

    private CodeBlock conversion(VariableElement field, String value) {
        String type = typeName(field.asType());
        return switch (type) {
            case "java.lang.String" -> CodeBlock.of("$N", value);
            case "boolean" -> CodeBlock.of("$T.booleanValue($N)", INPUT_VALUES, value);
            case "int", "java.lang.Integer" -> CodeBlock.of("$T.parseInt($N)", Integer.class, value);
            case "long", "java.lang.Long" -> CodeBlock.of("$T.parseLong($N)", Long.class, value);
            case "pl.kuba6000.ae2webintegration.core.identity.StableKey" -> CodeBlock
                .of("$T.parse($N)", STABLE_KEY, value);
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
        return annotationNamed(element, CONTRACT + name);
    }

    private AnnotationMirror annotationNamed(Element element, String name) {
        for (AnnotationMirror annotation : element.getAnnotationMirrors()) {
            if (annotation.getAnnotationType()
                .toString()
                .equals(name)) return annotation;
        }
        return null;
    }

    private String value(AnnotationMirror annotation, String name) {
        return annotationValue(annotation, name).toString();
    }

    private Object annotationValue(AnnotationMirror annotation, String name) {
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : processingEnv.getElementUtils()
            .getElementValuesWithDefaults(annotation)
            .entrySet()) {
            if (entry.getKey()
                .getSimpleName()
                .contentEquals(name))
                return entry.getValue()
                    .getValue();
        }
        throw new IllegalArgumentException("Missing annotation value: " + name);
    }

    private ClassName companion(TypeElement owner) {
        return companion(owner, "_HttpBinding");
    }

    private ClassName companion(TypeElement owner, String suffix) {
        String packageName = processingEnv.getElementUtils()
            .getPackageOf(owner)
            .getQualifiedName()
            .toString();
        String simpleName = owner.getQualifiedName()
            .toString()
            .substring(packageName.length() + 1)
            .replace('.', '_');
        return ClassName.get(packageName, simpleName + suffix);
    }

    private void error(Element element, String message) {
        processingEnv.getMessager()
            .printMessage(Diagnostic.Kind.ERROR, message, element);
    }
}
