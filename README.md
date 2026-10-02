# systemone-kmp

[![status: released](https://img.shields.io/badge/status-released-brightgreen.svg)](https://central.sonatype.com/artifact/com.sierranevadalabs/systemone-kmp)
[![license: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

Kotlin Multiplatform SDK for the System One API — **typed questions with calibrated probabilities instead of
generated text.**

A System One model answers typed questions about a state: whether something is true (`noul`), which of a set of
options it is (`choice`), and where it falls on an ordered scale (`score`) — with calibrated probabilities
rather than generated text. This SDK speaks that wire contract to any provider: TypeSafe's Jev by default, and
Liquid AI's `d1` through `SystemOneProvider.Liquid`. Learn what TypeSafe can do in the
[TypeSafe docs](https://docs.typesafe.ai/). Every Kotlin example in this file is compiled by the test suite, so
it cannot drift from the API.

> **Status: released.** `0.1.0` is the first release of this artifact. The API is pre-1.0, so a minor release may
> still change it; pin the version you depend on.

| | |
|---|---|
| Coordinates | `com.sierranevadalabs:systemone-kmp` |
| Package | `com.sierranevadalabs.systemone.sdk` |
| Targets | JVM, Android, Apple (iOS `arm64`/`simulatorArm64`/`x64`, macOS `arm64`), Linux `x64`/`arm64`, JS (Node and browser) |
| License | MIT |

## Requirements

- Kotlin `2.3.21` — the exact version Ktor 3.5.2 builds and publishes against, and the version this module's
  metadata is compiled with
- Consumers: Kotlin `2.3`+ for the multiplatform targets, or Kotlin `2.2`+ for the JVM target alone; see
  [Compatibility](#compatibility)
- JVM: consumes Java 8 bytecode; building from source needs JDK 21
- Android: `minSdk 28` (`compileSdk 36`)
- JS: the `js` target runs on Node and in a browser. Node is the test host; browser callers supply the Ktor
  Fetch engine. A browser build cannot keep `apiKey` secret, so put a proxy in front of the API that adds the
  key server-side, or keep the key out of the browser
- Ktor and `kotlinx-serialization` arrive transitively; nothing else needs declaring

## Table of Contents

- [Quickstart](#quickstart)
- [Parity and differences from the Python and JavaScript SDKs](#parity-and-differences-from-the-python-and-javascript-sdks)
- [Error handling](#error-handling)
- [Running the tests](#running-the-tests)
- [Documentation](#documentation)
- [Compatibility](#compatibility)
- [Contributing](#contributing)
- [License](#license)

## Quickstart

Install the SDK (Gradle Kotlin DSL):

```kts
dependencies {
    implementation("com.sierranevadalabs:systemone-kmp:0.1.0")
}
```

The HTTP client is built on [Ktor](https://ktor.io), but Ktor types appear in exactly two opt-in configuration
parameters (`engine` and `httpClientConfig`) and nowhere else in the public surface.

Read your API key however you like — the SDK reads no environment — then ask one question of each primitive and
read the answers back:

```kotlin
import com.sierranevadalabs.systemone.sdk.ChoiceAnswer
import com.sierranevadalabs.systemone.sdk.ScoreAnswer
import com.sierranevadalabs.systemone.sdk.SystemOneClient
import com.sierranevadalabs.systemone.sdk.SystemOneConfig
import com.sierranevadalabs.systemone.sdk.choice
import com.sierranevadalabs.systemone.sdk.noul
import com.sierranevadalabs.systemone.sdk.score
import com.sierranevadalabs.systemone.sdk.systemOne

// The SDK reads no environment: read the key yourself and pass it. Pass baseUrl or defaultModel to override the
// provider's own, or provider = SystemOneProvider.Liquid to talk to Liquid AI; see Providers.
val client = SystemOneClient(SystemOneConfig(apiKey = "your-api-key"))

// The question carries its own id, so the question object is also the key its answer comes back under.
val category = choice("category", "What is this about?", mapOf("billing" to null, "technical" to null))
val urgent = noul("urgent", "Does this convey urgency?")
val tone = score("tone", "What is the tone?", listOf("calm", "neutral", "angry"))

client.use {
    val response = it.systemOne("I was charged twice. Please fix this ASAP.", category, urgent, tone)

    val c: ChoiceAnswer = response[category] // typed: throws AnswerTypeMismatchException, never a wrong type
    val s: ScoreAnswer? = response.answerOrNull(tone) // the non-throwing twin: null instead of throwing
    println("${c.choice} at confidence ${c.confidence}; tone ${s?.score}")
}
```

Every call is `suspend`, so wrap it in your own coroutine scope; a JVM caller who wants a blocking call writes
`runBlocking { }`. `SystemOneClient` is `AutoCloseable` and closes the HTTP engine it created, never one you
passed in.

### Logging

Logging is **off by default**. Turn it on with `SystemOneConfig(logLevel = LogLevel.Info)`. `info` writes one line
per call — method, path, status, duration and request id — and one line per retry; `debug` writes the same lines
today and is reserved for the byte-level logging of a later logger abstraction; `warn` and `error` write nothing,
and exist so a level that works against the Python or JavaScript SDK does not surprise a caller. A failed call is
logged too, at the level that is on, with the status it failed on.

No level writes a header, a body, the query string, or the API key. Every line is built from the method, path,
status, duration and request id alone, so there is no redaction table that can be incomplete.

## Providers

The wire contract is the same everywhere; a `SystemOneProvider` says where it lives. `SystemOneConfig` selects
one, and the default is TypeSafe.

```kotlin
import com.sierranevadalabs.systemone.sdk.SystemOneClient
import com.sierranevadalabs.systemone.sdk.SystemOneConfig
import com.sierranevadalabs.systemone.sdk.SystemOneProvider
import com.sierranevadalabs.systemone.sdk.noul
import com.sierranevadalabs.systemone.sdk.systemOne

// TypeSafe: the default. Uses https://api.typesafe.ai and jev-latest.
val typesafe = SystemOneClient(SystemOneConfig(apiKey = "your-api-key"))

// Liquid AI d1: posts to /decisions/v1/systemone and uses d1:free.
val liquid =
    SystemOneClient(
        SystemOneConfig(apiKey = "your-liquid-api-key", provider = SystemOneProvider.Liquid),
    )

val urgent = noul("urgent", "Does this convey urgency?")
liquid.use { it.systemOne("Help! My payouts have been failing for 3 days.", urgent) }
```

| | TypeSafe (default) | Liquid |
|---|---|---|
| Origin | `https://api.typesafe.ai` | `https://api.liquid.ai` |
| Route | `/v1/systemone` | `/decisions/v1/systemone` |
| Default model | `jev-latest` | `d1:free` |
| Fingerprint headers | `X-TypeSafe-SDK`, `X-TypeSafe-Runtime` | none |

Any compatible server is one value, with no new release. The SDK reads no environment, so you name every value:

```kotlin
import com.sierranevadalabs.systemone.sdk.SystemOneProvider

val ollama = SystemOneProvider(baseUrl = "http://localhost:11434", defaultModel = "nimble")
```

Every request carries `User-Agent: systemone-sdk-kotlin/<version>`, whichever provider is selected. A provider
that declares no fingerprint headers sends none.

## Parity and differences from the Python and JavaScript SDKs

- **Noul criteria are ported.** A `noul` question describes its yes and no outcomes with `NoulCriteria`, encoded
  exactly as the siblings encode them: a `criteria` object whose undescribed sides are omitted, never `null`.
- **Suspend-only.** There is no blocking client, and no `AsyncSystemOneClient` twin. Python's sync/async pair is
  two near-verbatim clients kept in step by hand; Kotlin needs one.
- **Typed question keys.** The question object *is* the key: `choice("category", …)` is both the request and the
  statically typed key `response[category]` returns a `ChoiceAnswer` from. The siblings put the id in the outer
  key of the `questions` map instead, which Kotlin cannot mirror while keeping the answer statically typed.
- **Retry is Ktor's built-in `HttpRequestRetry`, driven by our `RetryPolicy`.** One retry loop, not two, and a
  `Retry-After` larger than `maxRetryAfter` falls back to the backoff rather than waiting for it — Python honours
  the server's value uncapped.

  ```kotlin
  import com.sierranevadalabs.systemone.sdk.RetryPolicy
  import com.sierranevadalabs.systemone.sdk.SystemOneClient
  import com.sierranevadalabs.systemone.sdk.SystemOneConfig
  import com.sierranevadalabs.systemone.sdk.noul
  import com.sierranevadalabs.systemone.sdk.systemOne
  import kotlin.time.Duration.Companion.seconds

  val client = SystemOneClient(SystemOneConfig(apiKey = "your-api-key", retry = RetryPolicy(maxRetries = 4, maxRetryAfter = 30.seconds)))
  val urgent = noul("urgent", "Does this convey urgency?")

  // A single call can replace the retry policy and the per-attempt timeout without touching the client.
  client.systemOne(
      "Help! My payouts have been failing for 3 days.",
      urgent,
      retry = RetryPolicy(maxRetries = 0),
      timeout = 30.seconds,
  )

  // A call can add its own headers too, over the client's defaults and under the SDK's own.
  client.systemOne("Retry me.", urgent, headers = mapOf("X-Request-Id" to "req-1"))

  // The model catalogue takes the same per-call overrides.
  client.models.list(timeout = 30.seconds, retry = RetryPolicy(maxRetries = 0))
  ```

- **Per-call headers are the siblings' `extra_headers` and `RequestOptions.headers`.** `systemOne` and
  `models.list` take `headers = …`. They merge over `defaultHeaders` and under the SDK's own, so a caller cannot
  replace `Authorization`, `Accept` or `Content-Type`.
- **A `score` question needs at least two levels.** Fewer is rejected locally with an `IllegalArgumentException`
  instead of spending a `422`; both SDKs require it on the wire.
- **The client reports its resolved settings.** `baseUrl`, `defaultModel`, `timeout`, `retry`, `logLevel` and
  `defaultHeaders` read back the value that actually took effect after `explicit → provider default` — six
  of the settings the JavaScript SDK exposes. The API key has no accessor, here or there.
- **No log line can carry a header, a body or the API key.** Logging is off by default — a deliberate divergence
  from the JavaScript SDK, whose default is `warn` — and when it is enabled, `info` reports one line per call
  (method, path, status, duration, request id) and one line per retry. Nothing else is formatted at all, so no
  blacklist of header names can be incomplete.
- **The public API dump is committed.** `api/` is compared on every build, so the surface cannot change without
  a reviewed diff.

## Error handling

Every failure the SDK raises is a `SystemOneError`. The classes match the Python and JavaScript SDKs by name, so an
existing `catch` block ports by name; only the root is ours.

```kotlin
import com.sierranevadalabs.systemone.sdk.SystemOneClient
import com.sierranevadalabs.systemone.sdk.SystemOneConfig
import com.sierranevadalabs.systemone.sdk.errors.AuthenticationError
import com.sierranevadalabs.systemone.sdk.errors.SystemOneError
import com.sierranevadalabs.systemone.sdk.errors.RateLimitError
import com.sierranevadalabs.systemone.sdk.noul
import com.sierranevadalabs.systemone.sdk.systemOne

val client = SystemOneClient(SystemOneConfig(apiKey = "your-api-key"))
val urgent = noul("urgent", "Does this convey urgency?")

try {
    client.systemOne("Help! My payouts have been failing for 3 days.", urgent)
} catch (e: RateLimitError) {
    println("throttled; the server asked for ${e.retryAfterMs} ms, request id ${e.requestId}")
} catch (e: AuthenticationError) {
    println("the key was rejected: ${e.message}")
} catch (e: SystemOneError) {
    println("the call failed: ${e.message}")
}
```

`APIError` carries `status`, `body` and `requestId`; its subclasses are `BadRequestError`,
`AuthenticationError`, `PermissionDeniedError`, `NotFoundError`, `UnprocessableEntityError`, `RateLimitError`
and `InternalServerError`. `APIResponseValidationError` is a 200 whose body did not match the wire contract. It
names the offending field in `fieldPath`, the name the Python SDK uses.
`APIConnectionError` — with `APITimeoutError` as its subclass — covers the failures where no response arrived,
and they carry the engine's own failure as `cause`. A caller's cancellation is a `CancellationException` and is
never wrapped.

## Running the tests

```bash
./gradlew check
```

`check` runs ktlint, the public-API dump comparison, the Dokka KDoc gate, the version/CHANGELOG consistency
check, the Java 8 bytecode assertion, the coverage floor, and every test target the current host can execute.
The conformance suite replays `conformance/` — the shared cross-language wire fixtures — through the real client
over Ktor's `MockEngine`, so it needs no network and no API key.

The live tier hits a paid API. It runs only on an explicit request: both a Gradle property **and** an
environment variable are required, and no CI workflow runs it. A default `./gradlew check` can never reach the
network or spend money.

```bash
TYPESAFE_API_KEY=… ./gradlew jvmTest -Ptypesafe.live=true
```

### Mutation testing, on demand

```bash
./gradlew pitestJvm
```

PIT mutates the JVM compilation of `commonMain` and writes `build/reports/pitest/` (read `mutations.xml`; the
HTML report mis-attributes line numbers for inlined Kotlin). It re-runs the suite once per mutant and takes
about two minutes, so it is deliberately **not** part of `check`. Run it before a release; read the test
strength it prints, then every survivor, because a surviving mutant is a behaviour no test observes.

### Proving the JVM floor, on demand

```bash
./gradlew verifyConsumerJvmFloor
```

It downloads the Kotlin 2.1 and 2.2 compilers and compiles a small consumer of the JVM artifact in a subprocess,
so no old compiler loads into the Gradle daemon. Kotlin 2.2 must compile it, and Kotlin 2.1 must reject it on the
metadata version. The two compiler downloads are the reason it is deliberately **not** part of `check`; run it when
the Kotlin floor or the toolchain moves. `verifyConsumerFloor`, the offline half of the same claim, is part of
`check`.

## Documentation

Learn what TypeSafe can do in the [TypeSafe docs](https://docs.typesafe.ai/). This SDK's own API reference is
KDoc on every public declaration — `explicitApi(Strict)` and a Dokka `failOnWarning` gate keep it that way — and
Dokka HTML is built as a CI artifact. The fenced Kotlin blocks in this file are compiled by the test suite, so a
signature change that leaves them stale fails the build.

See [`CONTEXT.md`](CONTEXT.md) for the vocabulary this project uses for the API and the decisions behind it.

## Compatibility

### Kotlin consumer floor

A consumer needs **Kotlin 2.3 or newer**. The dependency chain sets that floor, not this module: Ktor 3.5.2 and
`kotlinx-serialization` 1.11.0 ship KLIB ABI 2.3.0 and JVM metadata 2.3.0, and KLIB ABI compatibility is
one-directional. A JVM-only consumer can use Kotlin 2.2, because a consumer compiler reads JVM metadata one
language version ahead of it. `verifyConsumerFloor` runs in `check`: it reads the built commonMain klib manifest
and the compiled JVM class, and fails if a compiler or language-version move changes either floor.

The public API dump under `api/` is committed and compared on every build. From `0.1.0` onward it is the
published baseline: any public-surface change must show up as a deliberate, reviewed diff rather than a silent
`apiDump`.

## Contributing

Issues and pull requests are welcome. Run `./gradlew check` before opening one — see
[Running the tests](#running-the-tests) for what it covers and how to run the opt-in live tier. Questions go to
[GitHub Issues](https://github.com/snevadalabs/systemone-kmp/issues).

## License

MIT © Sierra Nevada Labs. See [LICENSE](LICENSE).
