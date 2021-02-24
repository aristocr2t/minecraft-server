FROM ubuntu:focal

WORKDIR /app

RUN apt-get update && apt-get install -y -qq wget curl openjdk-8-jdk-headless >/dev/null

ARG JAR_FILE_URL
RUN wget -O server.jar ${JAR_FILE_URL}

RUN java -version

CMD ["java", "-Xmx1024M", "-Xms1024M", "-jar", "server.jar", "nogui"]
