#!/usr/bin/env bash
# Fetch the compile/test dependencies into lib/ (for the Gradle-free build).
# Montoya is compile-only; gson and jsoup are bundled into the extension jar.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p lib
base="https://repo1.maven.org/maven2"

fetch() { # url dest
  if [ -f "lib/$2" ]; then echo "have $2"; else echo "get $2"; curl -fsSL "$1" -o "lib/$2"; fi
}

fetch "$base/com/google/code/gson/gson/2.11.0/gson-2.11.0.jar" gson-2.11.0.jar
fetch "$base/org/jsoup/jsoup/1.18.1/jsoup-1.18.1.jar" jsoup-1.18.1.jar
fetch "$base/net/portswigger/burp/extensions/montoya-api/2025.5/montoya-api-2025.5.jar" montoya-api.jar
fetch "$base/org/junit/platform/junit-platform-console-standalone/1.11.3/junit-platform-console-standalone-1.11.3.jar" junit-console-1.11.3.jar
echo "dependencies ready in lib/"
