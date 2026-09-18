# Same layout as the registry.gitlab.com/packaging/signal-cli images: user 101:101
# with home /var/lib/signal-cli, and /usr/bin/signal-cli with no --config default.
# It can replace them on an existing data volume; ./Containerfile cannot.
ARG JAVA_IMAGE=docker.io/azul/zulu-openjdk:25-latest@sha256:8eca9375451a392bff01efe946f2e9263c50aa71a9d68423c068cc1061a41b7e

FROM ${JAVA_IMAGE} AS build
WORKDIR /src
COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY gradle/ gradle/
COPY buildSrc/build.gradle.kts buildSrc/
COPY buildSrc/src/ buildSrc/src/
COPY src/ src/
COPY lib/build.gradle.kts lib/
COPY lib/src/ lib/src/
RUN sh gradlew --no-daemon :installDist

FROM ${JAVA_IMAGE}
LABEL org.opencontainers.image.description="signal-cli with the file layout of the registry.gitlab.com/packaging/signal-cli images"
LABEL org.opencontainers.image.licenses=GPL-3.0-only
RUN groupadd -g 101 signal-cli \
	&& useradd -u 101 -g 101 -M -d /var/lib/signal-cli -s /usr/sbin/nologin signal-cli \
	&& install -d -o signal-cli -g signal-cli -m 750 /var/lib/signal-cli
COPY --from=build /src/build/install/signal-cli /opt/signal-cli
RUN ln -s /opt/signal-cli/bin/signal-cli /usr/bin/signal-cli
USER signal-cli
ENTRYPOINT ["/usr/bin/signal-cli"]
