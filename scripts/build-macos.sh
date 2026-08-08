#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_root"

# Keep packaging independent from the normal test gate. This makes a runner-only
# integration-test failure diagnosable without withholding a valid DMG artifact.
./gradlew -PmacPackageVersion="${MAC_PACKAGE_VERSION:-2.0.0}" clean :desktop:compileKotlin :desktop:packageDmg --no-daemon
