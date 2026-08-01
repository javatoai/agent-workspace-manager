#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_root"

./gradlew -PmacPackageVersion="${MAC_PACKAGE_VERSION:-1.4.0}" clean test :cli:distZip :desktop:packageDmg --no-daemon
