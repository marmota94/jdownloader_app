FROM eclipse-temurin:17-jdk-jammy@sha256:acf46b4e646df812fd95c54669bc80d44acef68f0bde1dd420ea47897de0d637 AS sdk
ARG COMMANDLINE_TOOLS=11076708
ENV ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk
ENV PATH=$PATH:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools
RUN apt-get update && apt-get install -y --no-install-recommends curl unzip make ca-certificates && rm -rf /var/lib/apt/lists/*
RUN mkdir -p /opt/android-sdk/cmdline-tools && curl -fsSL https://dl.google.com/android/repository/commandlinetools-linux-${COMMANDLINE_TOOLS}_latest.zip -o /tmp/tools.zip && unzip -q /tmp/tools.zip -d /tmp/tools && mv /tmp/tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest && rm -rf /tmp/tools /tmp/tools.zip
RUN yes | sdkmanager --licenses >/dev/null
RUN sdkmanager 'platform-tools' 'platforms;android-35' 'build-tools;35.0.0'
FROM sdk AS android-builder
WORKDIR /app
COPY . /app
RUN chmod +x gradlew scripts/*.sh && ./gradlew --no-daemon help
ENTRYPOINT ["/bin/sh", "/app/scripts/container-build.sh"]
CMD ["./gradlew", "assembleDebug"]
