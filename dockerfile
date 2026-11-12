FROM eclipse-temurin:25-jre

RUN apt-get update && apt-get install -y --no-install-recommends \
  git \
  curl \
  && rm -rf /var/lib/apt/lists/*

WORKDIR /server/

RUN java -version
