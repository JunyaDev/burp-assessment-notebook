#!/usr/bin/env bash
# Build the loadable Burp extension jar without Gradle, using javac + jar.
# Produces build/assessment-notebook.jar with gson and jsoup bundled in.
# Montoya is NOT bundled (Burp provides it at runtime).
set -euo pipefail
cd "$(dirname "$0")/.."

[ -f lib/montoya-api.jar ] || scripts/fetch-deps.sh

CP="lib/gson-2.11.0.jar:lib/jsoup-1.18.1.jar:lib/montoya-api.jar"
rm -rf build/classes build/jar
mkdir -p build/classes build/jar

echo "==> compiling sources"
find src/main/java -name '*.java' > build/sources.txt
javac -d build/classes -cp "$CP" @build/sources.txt

echo "==> bundling gson + jsoup"
( cd build/jar && unzip -oq ../../lib/gson-2.11.0.jar && unzip -oq ../../lib/jsoup-1.18.1.jar )
# Drop bundled metadata that must not leak into our jar.
rm -rf build/jar/META-INF/MANIFEST.MF build/jar/META-INF/*.SF \
       build/jar/META-INF/*.RSA build/jar/META-INF/*.DSA build/jar/module-info.class
find build/jar -name 'module-info.class' -delete

echo "==> adding compiled classes and resources"
cp -r build/classes/. build/jar/
cp -r src/main/resources/. build/jar/    # our META-INF/services + assets win

echo "==> packaging jar"
mkdir -p build/libs
( cd build/jar && jar --create --file ../libs/assessment-notebook.jar . )
echo "built build/libs/assessment-notebook.jar"
