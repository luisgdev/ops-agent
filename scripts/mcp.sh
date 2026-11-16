#!/usr/bin/env bash
# Start the Rya Ops Agent as an MCP server on stdio (no API key needed).
# MCP clients launch this themselves; see "Use it as an MCP server" in the README.
# Env: OPSAGENT_DRY_RUN=true, OPSAGENT_AUTO_APPLY=false, OPSAGENT_DATA_DIR, OPSAGENT_AUDIT_FILE
set -euo pipefail
cd "$(dirname "$0")/.."

JAR=target/ops-agent.jar
if [ ! -f "$JAR" ] || [ -n "$(find src pom.xml -newer "$JAR" -type f 2>/dev/null | head -1)" ]; then
  echo "Building..." >&2
  mvn -q -DskipTests package >&2
fi

exec java -cp "$JAR" -Dloader.main=digital.rya.opsagent.mcp.McpMain \
  org.springframework.boot.loader.launch.PropertiesLauncher
