#!/bin/sh
# Сборка seasonsbar.jar в Docker (JDK 25 — под api-version 26.3).
# lib/*.jar — только для компиляции, в jar не попадают и в рантайме не нужны.
set -e
cd "$(dirname "$0")"
rm -rf out seasonsbar.jar
mkdir -p out
docker run --rm -v "$PWD":/app -w /app eclipse-temurin:25-jdk sh -c '
  CP=$(ls lib/*.jar | tr "\n" ":") &&
  javac -encoding UTF-8 --release 25 -Xlint:-deprecation -cp "$CP" -d out $(find src -name "*.java") &&
  jar --create --file seasonsbar.jar -C out . -C . plugin.yml -C . config.yml'
ls -l seasonsbar.jar
