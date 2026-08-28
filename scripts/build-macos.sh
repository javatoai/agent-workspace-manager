#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_root"

# A DMG is emitted only after the same gate used by Windows succeeds.
gradle_args=(clean test :desktop:compileKotlin :desktop:packageDmg --no-daemon)
if [[ "${AWM_RELEASE_SKIP_UNSTABLE_GIT_TESTS:-}" == "true" ]]; then
  gradle_args+=("-PskipHostedGitIntegrationTests")
fi
if [[ "${AWM_RELEASE_SKIP_MACOS_GENBU_PERMISSION_FIXTURE_TESTS:-}" == "true" ]]; then
  gradle_args+=("-PskipMacOsGenbuPermissionFixtureTests")
fi
./gradlew "${gradle_args[@]}"
