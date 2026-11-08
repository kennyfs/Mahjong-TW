# The battle server: the C++ engine (`mahjong gui`) and the Kotlin server around it; and the site: the web page (web/,
# its engine as WebAssembly) served by Caddy, which passes /ws on to the server. Nothing here needs ONNX Runtime yet;
# when a network joins the engine, Thirteen's server/Dockerfile shows where it goes (a model stage, ORT in the engine
# and web-engine stages).
# Build from the repository root: docker compose -f server/compose.yml build (see docs/battle.md), or one target:
#   docker build -f server/Dockerfile --target runtime -t IMAGE .
#   docker build -f server/Dockerfile --target site -t IMAGE .
# The build argument JOBS caps the compile jobs of each C++ build (by default every core; two builds run at once).

# The engine, without -march=native so the image runs on any x86-64 host; its unit tests must pass.
FROM ubuntu:26.04 AS engine
RUN apt-get update && apt-get install -y --no-install-recommends build-essential cmake ca-certificates \
  && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY cpp cpp
ARG JOBS
RUN cmake -S cpp -B cpp/build -DCMAKE_BUILD_TYPE=Release -DUSE_NATIVE=OFF \
  && cmake --build cpp/build -j"${JOBS:-$(nproc)}" --target mahjong \
  && cpp/build/mahjong runtests

# The server's tests that need the engine skip here (no binary in this image); run them on a machine with
# cpp/build/mahjong (docs/battle.md).
FROM gradle:8.14.3-jdk17 AS server
WORKDIR /src
COPY protocol protocol
COPY server/settings.gradle.kts server/build.gradle.kts server/
COPY server/src server/src
RUN gradle -p server --no-daemon :protocol:test test installDist

# ---- The web page ----

# The engine (cpp/ and GuiEngine) as WebAssembly.
FROM emscripten/emsdk:6.0.13 AS web-engine
RUN apt-get update && apt-get install -y --no-install-recommends ninja-build python3-venv \
  && rm -rf /var/lib/apt/lists/* \
  && python3 -m venv /opt/tools && /opt/tools/bin/pip install --no-cache-dir cmake==4.4.4
ENV PATH=/opt/tools/bin:$PATH
WORKDIR /src
COPY cpp cpp
COPY web/engine web/engine
COPY web/build-engine.sh web/
# The emsdk image has its tools on PATH already; the script reads JOBS.
ARG JOBS
RUN web/build-engine.sh

# The page, built by Vite with the app's strings.
FROM node:24-slim AS web-page
WORKDIR /src/web
COPY web/package.json web/package-lock.json ./
RUN npm ci
# Only the strings.xml of each language (.dockerignore).
COPY android/app/src/main/res /src/android/app/src/main/res
COPY web/ ./
COPY --from=web-engine /src/web/engine-files engine-files
RUN npm run build

# Caddy with the page in /srv and its Caddyfile.
FROM caddy:2.8 AS site
# Without the binary's file capability (to bind ports below 1024), which a container without capabilities cannot run:
# a copy has none.
RUN cp /usr/bin/caddy /usr/bin/caddy.new && mv /usr/bin/caddy.new /usr/bin/caddy
COPY server/Caddyfile /etc/caddy/Caddyfile
COPY --from=web-page /src/web/dist /srv

# ---- The battle server ----

# A JRE of only the modules the server uses (jdeps finds them in its jars), about 150 MB less than the whole one.
FROM ubuntu:26.04 AS jre
RUN apt-get update && apt-get install -y --no-install-recommends openjdk-21-jdk-headless binutils \
  && rm -rf /var/lib/apt/lists/*
COPY --from=server /src/server/build/install/mahjong-server/lib /server-lib
# jdeps may print warnings (split packages) before the list: the list is its last line, checked.
RUN modules=$(jdeps --ignore-missing-deps --print-module-deps --multi-release 21 /server-lib/*.jar 2>/dev/null | tail -n 1) \
  && echo "modules: $modules" && echo "$modules" | grep -Eq '^[a-z][a-z0-9.]*(,[a-z][a-z0-9.]*)*$' \
  && jlink --add-modules "$modules,jdk.unsupported" --strip-debug --no-man-pages --no-header-files --compress zip-6 \
    --output /opt/jre

# The engine needs the C++ runtime of the system it was built on: the same Ubuntu, with the JRE above.
FROM ubuntu:26.04 AS runtime
COPY --from=jre /opt/jre /opt/jre
RUN useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin mahjong
COPY --from=engine /src/cpp/build/mahjong /opt/mahjong/mahjong
COPY --from=server /src/server/build/install/mahjong-server /opt/mahjong/server
ENV JAVA_HOME=/opt/jre \
    PATH=/opt/jre/bin:$PATH \
    MAHJONG_BINARY=/opt/mahjong/mahjong \
    JAVA_OPTS="-Xmx128m -Xss512k -XX:+UseSerialGC -XX:ReservedCodeCacheSize=32m -XX:MaxMetaspaceSize=64m -XX:TieredStopAtLevel=1 -XX:+ExitOnOutOfMemoryError"
USER mahjong
EXPOSE 8080
ENTRYPOINT ["/opt/mahjong/server/bin/mahjong-server"]
