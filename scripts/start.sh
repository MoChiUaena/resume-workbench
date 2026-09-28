#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
export PLAYWRIGHT_BROWSERS_PATH="$PWD/.tools/ms-playwright"
export PLAYWRIGHT_SKIP_BROWSER_GC=1
if [ ! -f .env ]; then node -e "require('fs').writeFileSync('.env','RESUME_DB_PASSWORD='+require('crypto').randomBytes(24).toString('hex')+'\n')"; fi
RESUME_DB_PASSWORD="$(node -e "const m=require('fs').readFileSync('.env','utf8').match(/^RESUME_DB_PASSWORD=([a-zA-Z0-9_-]+)$/m);if(!m)process.exit(1);process.stdout.write(m[1])")"
export RESUME_DB_PASSWORD
docker compose -f compose.dev.yml up -d --wait
(cd frontend && npm ci && npm run build)
./mvnw -B package
./mvnw -B exec:java -Dexec.mainClass=com.microsoft.playwright.CLI '-Dexec.args=install chromium'
exec java -jar target/resume-workbench.jar
