#!/usr/bin/env bash
# Ask the agent from the terminal and print a readable report (no web server needed).
#   export ANTHROPIC_API_KEY=sk-ant-...
#   scripts/ask.sh [--dry-run] [--raw] [--no-color] [--verbose] [--out file.json] [--html report.html] ["question"]
# With no question it asks the Nov 4 question from the README (flags alone are fine).
set -euo pipefail
cd "$(dirname "$0")/.."

JAR=target/ops-agent.jar
if [ ! -f "$JAR" ] || [ -n "$(find src pom.xml -newer "$JAR" -type f 2>/dev/null | head -1)" ]; then
  echo "Building..." >&2
  mvn -q -DskipTests package >&2
fi

COLUMNS=${COLUMNS:-$(tput cols 2>/dev/null || echo 100)} \
  exec java -cp "$JAR" -Dloader.main=digital.rya.opsagent.cli.AskCli \
  org.springframework.boot.loader.launch.PropertiesLauncher "$@"
