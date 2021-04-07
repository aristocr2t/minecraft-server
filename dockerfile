FROM ubuntu:focal

WORKDIR /app

RUN apt-get update && apt-get install -y \
  git \
  wget \
  curl \
  openjdk-8-jdk-headless

RUN java -version
