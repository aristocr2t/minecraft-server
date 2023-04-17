FROM ubuntu:latest

RUN apt-get update && apt-get install -y \
  git \
  curl \
  libasound2 \
  openjdk-17-jre-headless

WORKDIR /server/

RUN java -version
