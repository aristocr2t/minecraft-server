#!/bin/sh
# Сборка thermo-1.0.0.jar в Docker (JDK 25 под api-version 26.3).
# lib/*.jar нужны только для компиляции: в jar не попадают и в рантайме не требуются.
set -e
cd "$(dirname "$0")"
rm -rf out thermo-1.0.0.jar
mkdir -p out
docker run --rm -v "$PWD":/app -w /app eclipse-temurin:25-jdk sh -c '
  CP=$(ls lib/*.jar | tr "\n" ":") &&
  javac -encoding UTF-8 --release 25 -Xlint:-deprecation -cp "$CP" -d out $(find src -name "*.java") &&
  jar --create --file thermo-1.0.0.jar -C out . -C . plugin.yml -C . config.yml'
ls -l thermo-1.0.0.jar
