FROM ubuntu:latest

RUN apt-get update && apt-get install -y \
  git \
  curl \
  libasound2 \
  openjdk-17-jre-headless

WORKDIR /server/

# ARG JDK_DEB_FILE

# COPY "${JDK_DEB_FILE}" ./
# RUN dpkg -i "/server/${JDK_DEB_FILE}" && rm -f "${JDK_DEB_FILE}"

# ARG JAVA_HOME

# RUN echo "\nJAVA_HOME=${JAVA_HOME}" >> /etc/profile
# RUN echo '\n\
# PATH=$PATH:$HOME/bin:$JAVA_HOME/bin\n\
# export JAVA_HOME\n\
# export JRE_HOME\n\
# export PATH\n\
# ' >> /etc/profile

# ENV PATH=$PATH:$HOME/bin:$JAVA_HOME/bin 

RUN java -version
