#!/usr/bin/env bash
# Ask the agent from the terminal and print a readable report (no web server needed).
#   export ANTHROPIC_API_KEY=sk-ant-...
#   scripts/ask.sh [--dry-run] [--raw] [--no-color] [--verbose] [--out file.json] ["question"]
# With no question it asks the Nov 4 question from the README.
set -euo pipefail
cd "$(dirname "$0")/.."

JAR=target/ops-agent.jar
if [ ! -f "$JAR" ] || [ -n "$(find src pom.xml -newer "$JAR" -type f 2>/dev/null | head -1)" ]; then
  echo "Building..." >&2
  mvn -q -DskipTests package >&2
fi

DEFAULT='Call volume on Nov 4 was well above Nov 2, but revenue and margin went down. Why, how much did each cause cost us, and what should we do about it?'
ARGS=("$@")
# add the default question if the last argument is a flag (or there are no arguments)
if [ $# -eq 0 ] || [[ "${!#}" == --* ]]; then ARGS+=("$DEFAULT"); fi

COLUMNS=${COLUMNS:-$(tput cols 2>/dev/null || echo 100)} \
  exec java -cp "$JAR" -Dloader.main=digital.rya.opsagent.cli.AskCli \
  org.springframework.boot.loader.launch.PropertiesLauncher "${ARGS[@]}"
