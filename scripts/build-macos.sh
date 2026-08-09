#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_root"

# A DMG is emitted only after the same complete gate used by Windows succeeds.
./gradlew -PmacPackageVersion="${MAC_PACKAGE_VERSION:-0.3.0}" clean test :desktop:compileKotlin :desktop:packageDmg --no-daemon
