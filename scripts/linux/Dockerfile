# Isolated test builder; package versions are captured after build. Final F-Droid
# dependency/recipe audit remains a separate release gate.
ARG MOTOAI_BASE=debian@sha256:ec31d744f83f4a73447ba045aaab9409997efe5cc706eca4890c1be36cc1606a
FROM ${MOTOAI_BASE}
RUN apt-get update -o APT::Update::Error-Mode=any
RUN DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ca-certificates curl git openjdk-17-jdk-headless python3 ninja-build unzip xz-utils
RUN dpkg-query -W > /builder-packages.txt
ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
WORKDIR /work
