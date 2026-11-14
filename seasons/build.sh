#!/bin/sh
# Сборка seasons-1.0.0.jar в Docker (JDK 25 — под api-version 26.3).
# lib/*.jar — classpath для компиляции; в готовый jar вшивается только драйвер SQLite.
set -e
cd "$(dirname "$0")"
mkdir -p out
docker run --rm -v "$PWD":/app -w /app eclipse-temurin:25-jdk sh -c '
  set -e
  rm -rf out seasons-1.0.0.jar
  mkdir -p out
  CP=$(ls lib/*.jar | tr "\n" ":") &&
  javac -encoding UTF-8 --release 25 -Xlint:-deprecation -cp "$CP" -d out $(find src -name "*.java") &&
  cd out && jar --extract --file ../lib/sqlite-jdbc.jar && rm -f module-info.class &&
  cd .. && jar --create --file seasons-1.0.0.jar -C out . -C . plugin.yml -C . config.yml -C . README.md'
ls -l seasons-1.0.0.jar
