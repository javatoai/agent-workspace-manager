#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_root"

./gradlew clean test :cli:distZip :desktop:packageDmg --no-daemon
