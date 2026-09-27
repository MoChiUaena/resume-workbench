#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
export PLAYWRIGHT_BROWSERS_PATH="$PWD/.tools/ms-playwright"
export PLAYWRIGHT_SKIP_BROWSER_GC=1
(cd frontend && npm ci && npm run build)
./mvnw -B package
./mvnw -B exec:java -Dexec.mainClass=com.microsoft.playwright.CLI '-Dexec.args=install chromium'
exec java -jar target/local-resume-0.1.0-SNAPSHOT.jar
