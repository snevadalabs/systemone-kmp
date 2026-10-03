#!/usr/bin/env bash
# Releases the version in gradle.properties to Maven Central: the same steps
# .github/workflows/publish.yml runs for a `v*` tag, from this machine.
#
# Signing uses the same credentials the workflow passes as ORG_GRADLE_PROJECT_*, read here from
# ~/.gradle/gradle.properties (or the environment):
#   mavenCentralUsername / mavenCentralPassword       the Central Portal user token
#   signingInMemoryKey / signingInMemoryKeyPassword   the ASCII-armored secret key
# There is no signingInMemoryKeyId: KGP's checkSigningConfiguration resolves that value on a
# keyserver, and Hockeypuck does not answer a 32-bit short id. Gradle signs correctly without it.
# The checkSigningKey task is a dependency of every publish task and reads the issuer out of each
# produced signature, so a wrong key fails before anything leaves the machine.
#
#   ./scripts/release.sh --check   build signatures and gate the key; upload nothing
#   ./scripts/release.sh           upload, release the deployment, tag, push the tag
set -euo pipefail
cd "$(dirname "$0")/.."

check_only=false
[[ ${1:-} == --check ]] && check_only=true

version=$(sed -nE 's/^version=(.*)$/\1/p' gradle.properties)
[[ -n $version ]] || { echo "no version= in gradle.properties" >&2; exit 1; }
[[ $version != *-SNAPSHOT ]] || { echo "refusing to release the snapshot $version" >&2; exit 1; }
command -v gpg >/dev/null || { echo "gpg is required by checkSigningKey" >&2; exit 1; }

if $check_only; then
    ./gradlew checkVersion checkSigningKey
    exit 0
fi

git diff --quiet && git diff --cached --quiet || { echo "working tree is dirty; commit first" >&2; exit 1; }
[[ $(git rev-parse --abbrev-ref HEAD) == main ]] || { echo "not on main" >&2; exit 1; }
if git rev-parse -q --verify "refs/tags/v$version" >/dev/null; then
    echo "tag v$version already exists; bump the version" >&2
    exit 1
fi

./gradlew checkVersion

# Non-gating, as in the workflow: read the test strength and every survivor, but a measurement
# must not fail a release.
./gradlew pitestJvm || true

# Uploads, then releases the deployment. A partial upload is deleted on the Portal, not re-released.
./gradlew publishAndReleaseToMavenCentral

# The tag lands after the upload, so the workflow finds the deployment on the Portal and skips the
# publish step. A tag with no upload would be the one state this script cannot reach.
git tag -a "v$version" -m "$version"
git push origin "v$version"
echo "systemone-kmp $version is uploaded, released and tagged."
