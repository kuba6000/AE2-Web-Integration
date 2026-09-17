# OpenAPI doclet

A standalone Java 17 tool that generates OpenAPI 3.1 JSON from endpoint annotations and Javadocs.
It runs at build time and is not included in the mod.

## Usage

Run from the standalone core checkout with JDK 17 available:

```shell
./gradlew generateApiDocs -PapiDocumentationVersion=<revision-or-release>
```

The result is `build/api-docs/openapi.json`. The version sets OpenAPI's `info.version`;
omitting it uses `development`.

Run the tool's tests independently:

```shell
./gradlew -p tools/openapi-doclet test
```

CI generates the specification and uploads it as the **openapi** artifact. The JSON can be used
with an OpenAPI viewer; this tool does not generate or deploy a documentation website.

## Endpoint declarations

`@Endpoint` supplies the HTTP method and path. The first Javadoc sentence becomes the summary,
and the remaining prose becomes the description. Linked response types describe the complete
response body; the generator adds no envelope.

```java
/**
 * Lists grids visible to the caller.
 *
 * @response 200 {@link Response} Accessible grids.
 * @response 401 {@link ErrorResponse} Missing credentials.
 * @responseExample 401 {"status":"UNAUTHORIZED","data":null}
 */
@Endpoint(method = HttpMethod.GET, path = "/api/grids")
public final class GetGrids {
    // Implementation and response DTOs.
}
```

| Declaration | Purpose |
| --- | --- |
| `@response <status> {@link Type} <description>` | Response schema and description. Omit the type for a response without a body. |
| `@responseExample <status> <JSON>` | Complete example overriding the generated response example. |
| `@PathParam("name")` and `@pathParam name Description.` | Path field binding and its endpoint Javadoc description. |
| `@Body` | Field whose DTO describes the required JSON request body. |
| `@OptionalInput` | Input member that may be omitted; explicit null remains disallowed. |
| `@Endpoint(authenticated = false, ...)` | Operation without an authentication requirement. |

Endpoint packages determine the operation categories through the doclet's category mapping.

## DTO descriptions and examples

Class and field Javadocs describe schemas and properties. For records, use
`@param name Description.` on the record to describe a component.

Use `@example value` on a field and `@keyExample key` on a map field.
For record components, include the component name: `@example owner ExamplePlayer` or
`@keyExample accessSources 095be615-a8ad-4c33-8e9c-c7612fbf6c9f`.
The generator combines these hints into request and response examples.
Use `@responseExample` when a response needs a specific complete example, such as an error.

In IntelliJ IDEA, add `response`, `responseExample`, `pathParam`, `example` and `keyExample`
to the additional Javadoc tags in the **Declaration has Javadoc problems** inspection.

## Supported schemas

The generator supports scalar types, UUID, StableKey, enums, concrete DTOs and records,
arrays, lists, sets and maps with String, UUID or StableKey keys. It respects
`@SerializedName`, omits static and transient fields, and uses `@Nullable` for response nullability.

Query bindings, custom Gson adapters, generic or polymorphic DTOs, and raw containers are
unsupported. Invalid declarations, unresolved response types and incompatible examples fail generation.
