# Changelog

All notable changes to this project are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The version declared in `gradle.properties` must match the top heading below: a `-SNAPSHOT` version requires an
`Unreleased` heading, and a release version requires the heading to name it. The `check-version` Gradle task
enforces this, and the publish workflow runs it before anything is uploaded.

`systemone-kmp` is a provider-neutral System One client. Version numbers restart here, so this file starts at
`0.1.0`.

## [0.1.0] - 2026-09-30

- First release of `com.sierranevadalabs:systemone-kmp`. The package is `com.sierranevadalabs.systemone.sdk`;
  the client is `SystemOneClient`, the configuration is `SystemOneConfig`, and the error root is
  `SystemOneError`.
- **New: `SystemOneProvider`.** `SystemOneConfig(provider = ...)` selects a deployment. The presets are
  `SystemOneProvider.TypeSafe` (the default) and `SystemOneProvider.Liquid`, which targets Liquid AI's
  `d1:free` behind `POST https://api.liquid.ai/decisions/v1/systemone`. A provider carries the origin, the
  route prefix, the default model, and the fingerprint headers, so a self-hosted or third-party System One
  server needs no code change.
- The SDK reads no process environment. `SystemOneConfig.apiKey` is a required value; `baseUrl`,
  `defaultModel` and `logLevel` fall back to the selected provider and then to the SDK default.
- The SDK sends `User-Agent: systemone-sdk-kotlin/<version>` on every request. It names this SDK, not the HTTP
  engine.
- A provider that declares no fingerprint headers sends none: a Liquid call carries no `X-TypeSafe-*` header
  and leaves `requestId` `null`.
- Typed questions and answers for all three System One primitives — `noul`, `choice`, and `score` — where the
  question object is itself the statically typed key for reading its answer. A `noul` question describes its
  yes and no outcomes with `NoulCriteria`.
- Retry with the JavaScript delay policy on Ktor's `HttpRequestRetry`, plus per-call `RetryPolicy`, `model`,
  `timeout` and `headers` overrides; `client.models.list()` takes the same per-call `timeout`, `retry` and
  `headers`.
- Opt-in logging: `LogLevel` and `SystemOneConfig.logLevel`, off by default, writing one line per call and one
  per retry and never a header, a body or the API key.
- Targets: JVM, Android, `iosArm64`, `iosSimulatorArm64`, `iosX64` (compile-only), `macosArm64`, `linuxX64`.
- A cross-language wire-conformance fixture suite in `conformance/`, and the committed
  binary-compatibility baseline at `api/jvm/systemone-kmp.api`.
- Kotlin 2.3 is the language and API version, matching the consumer floor the dependency chain sets: Ktor 3.5.2
  and `kotlinx-serialization` 1.11.0 ship KLIB ABI 2.3.0, and KLIB ABI compatibility is one-directional, so a
  JVM-only consumer can use Kotlin 2.2. `verifyConsumerFloor` guards the floor inside `check`;
  `verifyConsumerJvmFloor` proves the JVM half with the Kotlin 2.1 and 2.2 compilers.
