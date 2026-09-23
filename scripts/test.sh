#!/usr/bin/env bash
# Compile and run the JUnit 5 suite without Gradle.
set -euo pipefail
cd "$(dirname "$0")/.."

[ -f lib/junit-console-1.11.3.jar ] || scripts/fetch-deps.sh

JUNIT=lib/junit-console-1.11.3.jar
CP="lib/gson-2.11.0.jar:lib/jsoup-1.18.1.jar:lib/montoya-api.jar"

rm -rf build/classes build/test-classes
mkdir -p build/classes build/test-classes

echo "==> compiling main"
find src/main/java -name '*.java' > build/sources.txt
javac -d build/classes -cp "$CP" @build/sources.txt

echo "==> compiling tests"
find src/test/java -name '*.java' > build/test-sources.txt
javac -d build/test-classes -cp "build/classes:$CP:$JUNIT" @build/test-sources.txt

echo "==> running tests"
# src/main/resources on the classpath so bundled assets resolve at runtime.
java -jar "$JUNIT" execute \
  -cp "build/test-classes:build/classes:src/main/resources:lib/gson-2.11.0.jar:lib/jsoup-1.18.1.jar" \
  --scan-classpath --disable-banner --details=tree
