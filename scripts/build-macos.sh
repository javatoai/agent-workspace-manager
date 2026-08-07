#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_root"

./gradlew -PmacPackageVersion="${MAC_PACKAGE_VERSION:-2.0.0}" clean test :desktop:compileKotlin :desktop:packageDmg --no-daemon
