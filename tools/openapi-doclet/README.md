# OpenAPI doclet

This standalone Java 17 build generates viewer-neutral OpenAPI 3.1 JSON from endpoint source.
It is not a core subproject, runtime dependency, or mod server route. The output can be consumed
by an existing static viewer or a custom HTML viewer; generating it does not publish anything.

From the standalone core checkout, generate the specification with:

```shell
./gradlew generateApiDocs -PapiDocumentationVersion=<core-revision-or-release>
```

The result is `build/api-docs/openapi.json`. Omitting the version labels it `development`.
The generated operations follow the included endpoint sources and their method/path annotations.
The tool has no dependency on legacy routing or on the running mod.

Build and verify the tool independently with:

```shell
./gradlew -p tools/openapi-doclet test docletPath
```

The `docletPath` task puts the tool and its JSON writer dependencies in `build/doclet` under this
directory. Pass all those JARs on Javadoc's `-docletpath`; pass the core compile dependencies on
`-classpath`. These are separate classpaths. Invoke Javadoc on source with Java 17 syntax so that
Jabel `@Desugar` records are visible as records; do not run Jabel or use Java 8 source mode for this
build-time operation. Jabel's annotation JAR still belongs on the source compile classpath.

For example, in PowerShell with JDK 17+ on PATH:

```powershell
$docletPath = (Get-ChildItem tools/openapi-doclet/build/doclet/*.jar).FullName -join [IO.Path]::PathSeparator
# Set $coreCompileClasspath to the core compile dependency paths, separated by PathSeparator.
javadoc -quiet -private -encoding UTF-8 --source 17 `
  -doclet pl.kuba6000.ae2webintegration.doclet.OpenApiDoclet `
  -docletpath $docletPath -classpath $coreCompileClasspath `
  -sourcepath src/main/java -subpackages pl.kuba6000.ae2webintegration.core.http.endpoint `
  --output build/api-docs/openapi.json --api-version <core-revision-or-release>
```

`--output` and `--api-version` are required. The version identifies the source revision, not a URL
version. Supply the complete endpoint package to prevent accidental omission. There is no runtime
classpath scan. There is no runtime registration manifest to compare with the included
sources; endpoint registration/documentation consistency remains an integration-test responsibility.

## Client migration and authentication

All 16 operations use `/api` paths without a version segment. Legacy API routes, including `/grids`,
`/list`, `/get`, `/items`, `/gridsettings`, `/order`, `/job`, `/cancelcpu`, `/gettracking`,
`/trackinghistory` and the old `/auth` API, are removed. Website login/registration forms remain a
separate page flow. Update callers to the generated method/path contract; old query switches are not
aliases for the new operations.

| Method | Path | Input / result |
| --- | --- | --- |
| GET | `/api/grids` | Accessible grids |
| GET | `/api/grids/{gridKey}/cpus` | CPU summaries |
| GET | `/api/grids/{gridKey}/cpus/{cpuKey}` | CPU state |
| POST | `/api/grids/{gridKey}/cpus/{cpuKey}/cancel` | Cancel current CPU work; no body |
| GET | `/api/grids/{gridKey}/items` | Stored and craftable items |
| GET | `/api/grids/{gridKey}/settings` | Grid settings |
| PATCH | `/api/grids/{gridKey}/settings` | `{ "isTracked": true }`; omission leaves settings unchanged |
| POST | `/api/grids/{gridKey}/crafting-plans` | `{ "itemKey": "...", "quantity": 1 }`; 202 with `data.jobID` |
| GET | `/api/grids/{gridKey}/crafting-plans/{planId}` | Poll calculation; 200 may have `data.isDone: false` |
| DELETE | `/api/grids/{gridKey}/crafting-plans/{planId}` | Discard the plan; no body |
| POST | `/api/grids/{gridKey}/crafting-plans/{planId}/submit` | `{}` or `{ "cpuKey": "..." }` |
| GET | `/api/grids/{gridKey}/crafting-history` | Completed crafting history |
| GET | `/api/grids/{gridKey}/crafting-history/{entryId}` | One history entry |
| POST | `/api/auth/login` | `{ "username": "...", "password": "...", "rememberMe": false }` |
| POST | `/api/auth/register` | `{ "username": "...", "password": "..." }`; 202 with confirmation token |
| POST | `/api/auth/logout` | Revoke the current session; no body |

Responses keep the `{ "status": "...", "data": ... }` envelope. Read HTTP status and the machine-readable
`status` together; parse the JSON error body on non-2xx responses. Reads and completed mutations use 200;
plan creation and registration use 202. Registration still requires the in-game confirmation command.
Mutation responses without a payload use `data: null`. Plan/history numeric IDs retain their runtime
lifetime; stable grid/item/CPU keys stay opaque strings.

Login and registration are public JSON operations. Login returns a token for
`Authorization: Bearer <token>`; neither API operation installs a browser cookie. Existing page forms
manage an HttpOnly `authenticationToken` cookie at the page's external mount path. Explicit
Authorization takes precedence over the cookie; an invalid Bearer does not fall back to the cookie.
Configured local access is an authentication exception determined by the server's trusted-client policy.

Authenticated mutations using a cookie or configured local access require `X-AE2-Request: true`.
A valid Bearer request does not need that marker. Public login/registration do not require it. The
server permits no credentialed CORS and does not allow the marker in cross-origin preflight. Browser
clients should call the same-origin API and redirect to the website after logout to clear the cookie
at its original path. A PHP proxy must check the marker before converting the browser cookie to Bearer.

JSON bodies require `Content-Type: application/json` and must be at most 8192 UTF-8 bytes. Every current
runtime input is a flat object containing strings, booleans or integers. Nested objects/arrays, duplicate
members, unknown members, wrong scalar types, and explicit null are rejected. Optional members may be
omitted; supply `{}` for a required body whose members are all optional. The doclet can describe richer
recursive DTO graphs for responses and future inputs, but that schema capability does not expand the
runtime binder's flat-scalar input support.

For a website mounted at `/ae2/`, construct `api/...` URLs relative to the page, rather than `/api/...`
URLs relative to the host root. For example, a same-origin browser settings update is:

```javascript
const response = await fetch(`api/grids/${encodeURIComponent(gridKey)}/settings`, {
    method: 'PATCH',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json', 'X-AE2-Request': 'true' },
    body: JSON.stringify({ isTracked: true })
});
const result = await response.json();
if (!response.ok) throw new Error(result.status);
```

A reverse proxy maps the external mount's API paths to the mod's `/api` paths and preserves the method,
JSON body, Authorization/marker headers and response status. Preserve `Allow` and `WWW-Authenticate`
response headers. Configure trusted proxies explicitly when forwarding client addresses; proxying does
not itself establish player permission. Use the website's trailing-slash mount URL so relative links and
the default cookie path resolve under the same prefix.

Known paths answer OPTIONS with 204 and an `Allow` header; GET paths also support HEAD without a response
body. Unknown API paths return 404; unsupported methods on known paths return 405. Generated operation
responses document current binding, authorization, size/media, rate-limit and lifecycle errors alongside
domain failures. Examples illustrate one error code per HTTP status; descriptions list alternatives.

## Supported contract

The annotation is identified by the exact name
`pl.kuba6000.ae2webintegration.core.http.contract.Endpoint`. Its `method` enum member and `path`
string member supply the route. Response types come from compiler-resolved links:

```java
/**
 * Lists grids visible to the caller.
 *
 * @response 200 {@link Response} Accessible grids.
 * @response 400 {@link ErrorResponse} Invalid request.
 */
@Endpoint(method = HttpMethod.GET, path = "/api/grids")
public final class GetGrids {
    // The linked DTO is the entire response, including any status/data envelope.
}
```

A response without a body uses `@response 204` or `@response 204 Description.`. HTTP statuses
that forbid bodies reject a schema link. Duplicate status declarations and method/path pairs fail.
The first Javadoc sentence becomes the operation summary; remaining prose becomes the description.
Response descriptions may span lines. No envelope is added by the generator.

DTO class/record Javadocs become schema descriptions. Field Javadocs become property descriptions;
record components use the record's `@param name description` tags. These descriptions are optional
and do not change the response JSON. Inline `{@code ...}` is rendered as Markdown code.

### Path bindings and request bodies

A whole path segment may be a placeholder, such as `/api/grids/{gridKey}/settings`. Each placeholder
must match an inherited or directly declared `@PathParam("gridKey")` field and one endpoint Javadoc
`@pathParam gridKey Description.` tag. The field's Java type supplies its schema. Path parameters are
always required and nonnull. Unused inherited bindings are ignored so a shared request base can serve
routes with different variables. Missing, extra or duplicate descriptions/bindings fail generation.

One `@Body` field declares a required JSON request object. Its concrete DTO supplies the input schema;
field Javadoc describes the body. Input schemas have an `Input` suffix to keep request constraints
independent of response schemas when the same DTO is used in both directions. The generator preserves
all property descriptions and examples in both forms.

Input fields are required unless marked `@OptionalInput`. An optional member may be absent, but an
explicit JSON null is forbidden even when the Java field has `@Nullable` to represent omission.
This applies recursively to the input DTO graph. Input DTO schemas reject unknown properties through
`additionalProperties: false`. Response schemas retain the existing Gson `serializeNulls` semantics.
For example:

```java
/**
 * Updates grid settings.
 * @pathParam gridKey Stable grid identifier.
 * @response 200 {@link Response} Updated settings.
 */
@Endpoint(method = HttpMethod.PATCH, path = "/api/grids/{gridKey}/settings")
public final class UpdateGridSettings extends GridRequest {
    /** Settings to change; omitted members retain their current value. */
    @Body private Changes changes;

    /** @example isTracked true */
    public record Changes(@OptionalInput @Nullable Boolean isTracked) {}
}
```

The inherited `GridRequest` field carries `@PathParam("gridKey")` and type `StableKey`. Multiple body
fields, scalar bodies, static bindings, object-valued path parameters and malformed placeholders fail.
Query bindings and arbitrary request media types are not supported. Complete response examples still
use `@responseExample`; request examples are assembled from the input DTO's field/component hints.

### Examples alongside the DTOs

Field Javadocs accept `@example <value>` and maps accept `@keyExample <key>`. On a record, put the
same tags above the record with the component name first:

```java
/**
 * Grid information.
 * @example owner ExamplePlayer
 * @keyExample accessSources 095be615-a8ad-4c33-8e9c-c7612fbf6c9f
 */
```

Put the same UUID in `@example` on `PlayerIdentity.uuid` to keep the sample map and player consistent.
Strings may be unquoted text or JSON string literals; numbers and booleans retain their JSON types.
Use `null` for nullable fields (quote `"null"` when the intended value is the string). These hints
describe scalar values and map keys, not Java expressions, constructors or extra example classes.
Duplicate hints, unknown record components, invalid types/UUIDs and key hints on non-maps fail generation.

The generator expands these hints through references, objects and containers into an explicit media-type
`example` for each response and request body. This lets viewers display the chosen map key instead of synthesizing
`property1`. Schema properties retain their own OpenAPI 3.1 `examples` arrays as well. Unspecified values
use deterministic placeholders; a container gets one example entry where possible. Recursive links use
nullable/empty-container termination; a required non-null cycle has no finite example and is left without one.

An endpoint can override the assembled example for a particular response:

```java
/**
 * @response 401 {@link ErrorResponse} Missing credentials.
 * @responseExample 401 {"status":"UNAUTHORIZED","data":null}
 */
```

This is useful for error DTOs shared by several statuses. One complete JSON example is supported per
response code. The declared response must have a body. Examples are checked against the generator's
supported schemas before writing the output, including required fields, nullability, scalar/enum types
and map-key formats. This validates the documented shape, not relationships between arbitrary fields:
authors still choose matching UUIDs and error codes. Examples contain demonstration data only.

### Registration and editor support

Register the endpoint class in `AE2Controller.startHTTPServer()` using `api.register(Type.class)`.
The router reads the method and path from `@Endpoint`; they are not repeated in the registration.
Different methods may share a path, each with its own handler and Javadoc.

The doclet derives OpenAPI operation tags from the endpoint package. The categories, in documentation
order, are `grid` → **Grids**, `cpu` → **CPUs**, `crafting` → **Crafting plans**, `tracking` →
**Crafting history**, and `auth` → **Authentication**. Included categories also have top-level tag
descriptions. Viewers can group operations by these tags; their settings control the final presentation
and may override the declared ordering. A new endpoint category needs an entry in the doclet's category
mapping; individual endpoints do not repeat their category in annotations or Javadoc.

The custom tags are `response`, `responseExample`, `pathParam`, `example` and `keyExample`.
In IntelliJ IDEA, add those names to the additional Javadoc tags
in the **Declaration has Javadoc problems** inspection if the editor reports it as an unknown tag.
The doclet validates its syntax and linked response types during generation.

Schemas cover primitive/boxed scalars, strings, UUID strings, the project's StableKey string adapter,
Void as JSON null, enums, arrays, List/Set/Collection (including the existing DTOs' concrete
ArrayList and HashSet declarations), and Map with String/UUID/StableKey keys.
Maps use `additionalProperties` for their values and `propertyNames` for their string keys;
UUID keys retain `format: uuid`. A viewer may display a generic property-name placeholder for maps,
even when the specification describes the key format.
Concrete DTO fields (including record backing fields and inherited fields) are read through the
compiler model. Gson `SerializedName` values are respected; static and transient fields are omitted.
Schema names omit packages and prefix nested types with their enclosing type names, for example
`GetGridsResponse`. Operation IDs use the same names with an initial lowercase letter, e.g. `getGrids`.
Ambiguous schema names or operation IDs fail generation instead of overwriting another declaration.
Recursive DTO references use these schema names. Gson's project-wide `serializeNulls`
policy means every serialized field is required in the response object; JetBrains `Nullable` adds
JSON null as an allowed value. Unannotated reference fields are treated as nonnull contracts.

The document describes Bearer authentication OR the `authenticationToken` cookie. Its description
records the configured local-access exception. `@Endpoint(authenticated = false, ...)` overrides the
operation's security with an empty list, for public login and registration operations.

Unsupported custom block tags, malformed path placeholders, unresolved response links, generic DTOs,
wildcards/type variables, raw containers, arbitrary JDK objects, interfaces/abstract polymorphic
DTOs, nonstatic inner DTOs, custom Gson adapters, and unsupported map keys fail generation. Add
support alongside the first endpoint that needs it. Response links reference declared types, not
inline parameterized Java types: wrap a list response in a concrete DTO when needed. A rejected
contract does not replace an existing output file. Filesystem write failures are reported by Javadoc;
output replacement is not an atomic publishing operation.

## Dependencies and verification

Gson is the existing project's JSON library and handles JSON escaping; no custom JSON serializer is
introduced. It is a dependency of this tool only. Swagger Parser 2.1.48 is test-only: its upstream
[documentation](https://github.com/swagger-api/swagger-parser#openapi-31-support) records OpenAPI 3.1
support since 2.1.0. Tests validate emitted documents through that parser and compare representative
DTO properties with actual Gson serialization. They invoke Javadoc through the public JDK
`DocumentationTool`, including failure cases, and check repeat output bytes for determinism.

The output contains no generated timestamps, machine paths, server credentials, or world data.
It can be published separately on GitHub, GitHub Pages, or another static host. This tool does not
serve, deploy, or style the documentation.
