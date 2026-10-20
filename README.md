# webfunction-java

A Java client library for the [Web Function](https://webfunction.org) protocol.

Ported from and cross-checked against the official Ruby gem
([gem](https://github.com/webfunction-protocol/webfunction-ruby)) and
the [webfunction-go](https://github.com/webfunction-protocol/webfunction-go)
port, adjusted where Java's language model genuinely differs (real
exceptions, real generics, `Iterable`) or where a deliberate ecosystem-wide
design choice was made instead of following Ruby exactly.

## Requirements

- Java 17+
- Maven

## Status

Functionally complete for a first pass: package fetching (as an endpoint or
plain JSON), calling endpoints, pagination, typed exceptions, pipelining,
and recursive type validation. Not yet published anywhere.

## Usage

```java
Client client = Client.fromPackageEndpoint(
    "https://api.example.com/package",
    new Options().bearerAuth("token")
);

Object result = client.call("find-user", Map.of("id", "123"));
```

There are no generated, named methods (e.g. `client.findUser(...)`) - Java
has no clean equivalent of the reference clients' dynamic dispatch (Ruby's
`method_missing`, JS's `Proxy`, PHP's `__call`; `java.lang.reflect.Proxy`
only works over interfaces and would add real complexity for
questionable benefit). Named per-endpoint methods are the responsibility of
the wfn CLI's future `java` codegen target, built on top of this library -
same decision already made for webfunction-go.

### Pagination

An endpoint flagged `paginated` in the package definition returns a `Page`
instead of a raw value:

```java
Object result = client.call("list-people", Map.of());
Page page = (Page) result;

for (Object item : page) {
    // ...
}
if (page.hasNext()) {
    Optional<Page> next = page.nextPage();
}
```

**Deviation from the Ruby/JS/PHP reference clients**: they detect
pagination by inspecting each response's *shape* (`{page, next, previous}`),
regardless of any flag. This client (like webfunction-go before it) instead
trusts the endpoint's declared `paginated` flag and throws
`IllegalStateException` on a flag/shape mismatch. This is a deliberate,
ecosystem-wide choice, not an oversight.

### Errors

Every failure is an unchecked `WebFunctionException` subtype -
`BadRequestException`, `UnexpectedStatusCodeException`, `JsonParseException`,
`UnresolvedPromiseException` - each carrying `getCode()`, `getMessage()`,
and `getDetails()`:

```java
try {
    client.call("find-user", Map.of("id", "missing"));
} catch (BadRequestException e) {
    System.out.println(e.getCode() + ": " + e.getMessage());
}
```

**Unchecked, not checked**: a deliberate choice (discussed and confirmed) -
modern Java style, less call-site ceremony, at the cost of an uncaught
exception being able to propagate silently.

### Pipelining

```java
client.setPipeline(new Pipeline(client.getPackage().pipelineUrl()));
// or: Client.fromPackageEndpoint(url, new Options().pipelined(true))

Promise user = (Promise) client.call("find-user", Map.of("id", "123"));

Promise order = (Promise) client.call("create-order", Map.of(
    "user_id", user.field("id") // references the not-yet-resolved user's id
));

Object value = order.resolve(); // executes the batched pipeline, fills in both promises
```

**Simplification versus the reference clients**: `Promise.field`/`index`
always extend the JSONPath, even after the promise is resolved. Ruby's
`Promise#[]` behaves differently once resolved (indexing into the real,
already-decoded value) - replicating that in Java would need
reflection-heavy navigation of a decoded `Object` value, and wasn't
implemented. Call `value()` or `resolve()` to get the real value.

## Generics

A generic object (an object definition using the type parameter `T`) is applied
to a type as `{ "object.list": "object.user" }`. `Type` represents that as an
alternative with base `object`, refinement `list` and `arg()` set, printing as
`object.list<object.user>`; `objectNames()` includes both names. An array of a
type can be written `{ "array": "object.user" }`, which is the same as
`[["object.user"]]` (an array alternative with `of()` set, printing as
`array<object.user>`); a union of one entry is that entry. The library
doesn't resolve generics itself, and never fails on them: an unresolved `T`
reads as `any` (`TypeAlt.param()` is true, for tooling that wants to tell it
from a literal `any`), and a malformed application becomes `any` with the
reason in `TypeAlt.malformed()`.

## Known gaps / open questions

- **Events**: an earlier Go model (in the wfn CLI's own `webfunction/`
  package, since removed) included `Package.eventSourceUrl` and an `Event`
  type. Neither exists anywhere in the Ruby reference gem, nor in
  webfunction-js/webfunction-php. Whether "events" are a real, not-yet-
  implemented part of the spec, or an earlier over-read of the spec site
  without a reference implementation to check against, is unconfirmed.
  Left out of this library too, for consistency with webfunction-go.
- Not yet published/versioned anywhere.

## A note on JSON binding

Every model type (`Package`, `Endpoint`, `Argument`, etc.) is a Java
`record` with an explicit, `@JsonCreator`-annotated canonical constructor
naming each wire field via `@JsonProperty` - deliberately *not* relying on
Jackson's automatic record-parameter-name detection combined with a global
snake_case naming strategy. That combination was tried first and found to
throw an illegal-field-access error on Jackson 2.14 (available via this
project's dev sandbox as an apt package, standing in for Maven Central,
which the sandbox can't reach) - it fell back to setting the record's
final fields directly via reflection instead of using the constructor,
which records don't permit. Explicit property names sidestep the issue
regardless of Jackson version, and are the more portable choice for a
library other projects will depend on.

## Development

This project targets Maven + real Jackson/JUnit 5 from Maven Central
(see `pom.xml`). The development sandbox used to build this couldn't reach
Maven Central, so it was actually compiled and tested with the
apt-packaged Jackson 2.14 and JUnit 5.10 jars as a stand-in - functionally
equivalent for verification purposes, but `mvn test` against the real
declared versions is the authoritative check.

```
mvn test
mvn package
```