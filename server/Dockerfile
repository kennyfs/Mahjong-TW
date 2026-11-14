# The battle server: the C++ engine (`mahjong gui`) and the Kotlin server around it; and the site: the web page (web/,
# its engine and network as WebAssembly) served by Caddy. Both play the network: the server's bots through the engine
# with ONNX Runtime (Linux, the engine stage), the page's engine with ONNX Runtime for WebAssembly (the web-engine
# stage); each runtime and the network are fetched and checked by their SHA-256.
# Build from the repository root: docker compose -f server/compose.yml build (see docs/battle.md), or one target:
#   docker build -f server/Dockerfile --target runtime -t IMAGE .
#   docker build -f server/Dockerfile --target site -t IMAGE .
# Build arguments: JOBS caps the compile jobs of each C++ build (by default every core; two builds run at once);
# MODEL_URL, MODEL_SHA256: the network (the model stage). Works with the legacy builder (no BuildKit features).

# The network: server/model/actor.onnx when the build context has it (put there by hand, kept out of git), else
# MODEL_URL (a release asset of this repository's); either way it must match MODEL_SHA256 (network 221 of the run s3,
# its weights as float16). A new network: both arguments, here and in the release.
FROM ubuntu:26.04 AS model
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates && rm -rf /var/lib/apt/lists/*
ARG MODEL_URL=https://github.com/kennyfs/Mahjong-TW/releases/download/v1.0.0/actor_s3_221.fp16.onnx
ARG MODEL_SHA256=f1bce44e58cb47b65e95777c9039419f0c7b10f53d3600b791770253a63663db
COPY server/model /model
RUN if [ ! -f /model/actor.onnx ]; then curl -fsSL -o /model/actor.onnx "$MODEL_URL"; fi \
  && echo "$MODEL_SHA256  /model/actor.onnx" | sha256sum -c -

# The engine, without -march=native so the image runs on any x86-64 host, with ONNX Runtime 1.20.1 (Microsoft's
# release for Linux x64, the one the development machine builds with) for the network's bots; its unit tests must pass.
FROM ubuntu:26.04 AS engine
RUN apt-get update && apt-get install -y --no-install-recommends build-essential cmake curl ca-certificates \
  && rm -rf /var/lib/apt/lists/*
RUN curl -fsSL -o /ort.tgz https://github.com/microsoft/onnxruntime/releases/download/v1.20.1/onnxruntime-linux-x64-1.20.1.tgz \
  && echo "67db4dc1561f1e3fd42e619575c82c601ef89849afc7ea85a003abbac1a1a105  /ort.tgz" | sha256sum -c - \
  && mkdir /opt/onnxruntime && tar -xzf /ort.tgz -C /opt/onnxruntime --strip-components=1 && rm /ort.tgz
WORKDIR /src
COPY cpp cpp
ARG JOBS
RUN cmake -S cpp -B cpp/build -DCMAKE_BUILD_TYPE=Release -DUSE_NATIVE=OFF -DONNXRUNTIME_ROOT=/opt/onnxruntime \
  && cmake --build cpp/build -j"${JOBS:-$(nproc)}" --target mahjong \
  && cpp/build/mahjong runtests

# The server's tests that need the engine skip here (no binary in this image); run them on a machine with
# cpp/build/mahjong (docs/battle.md).
FROM gradle:8.14.3-jdk17 AS server
WORKDIR /src
COPY protocol protocol
# The rooms' pace: the screens' times (protocol/build.gradle.kts puts them among the protocol's resources).
COPY android/app/src/main/res/raw/timing.json android/app/src/main/res/raw/timing.json
COPY server/settings.gradle.kts server/build.gradle.kts server/
COPY server/src server/src
RUN gradle -p server --no-daemon :protocol:test test installDist

# ---- The web page ----

# The engine (cpp/ and GuiEngine) as WebAssembly, with the network (the model stage) beside it. It links ONNX Runtime
# 1.31.1 reduced to the operators these networks use, as a WebAssembly static library: Thirteen's release asset (its
# web/build-ort-wasm.sh; this network's operators are among them), the same bytes as the one the page was built with
# on the development machine.
FROM emscripten/emsdk:6.0.13 AS web-engine
RUN apt-get update && apt-get install -y --no-install-recommends ninja-build python3-venv curl ca-certificates \
  && rm -rf /var/lib/apt/lists/* \
  && python3 -m venv /opt/tools && /opt/tools/bin/pip install --no-cache-dir cmake==4.4.4
ENV PATH=/opt/tools/bin:$PATH
RUN curl -fsSL -o /ort-wasm.tar.gz https://github.com/kennyfs/Thirteen/releases/download/v2.1.0/ort-wasm-1.31.1.tar.gz \
  && echo "b8c7a39adb38f83137eebd39e84ffbbad434cc7218e7f53067e59e94977e67ce  /ort-wasm.tar.gz" | sha256sum -c - \
  && mkdir /opt/ort-wasm && tar -xzf /ort-wasm.tar.gz -C /opt/ort-wasm && rm /ort-wasm.tar.gz
COPY --from=model /model /model
WORKDIR /src
COPY cpp cpp
COPY web/engine web/engine
COPY web/build-engine.sh web/
# The emsdk image has its tools on PATH already; the script reads JOBS.
ARG JOBS
RUN ORT_WASM_DIR=/opt/ort-wasm MODEL=/model/actor.onnx web/build-engine.sh

# The page, built by Vite with the app's strings.
FROM node:24-slim AS web-page
WORKDIR /src/web
COPY web/package.json web/package-lock.json ./
RUN npm ci
# Only the strings.xml of each language and the times (raw/timing.json; .dockerignore).
COPY android/app/src/main/res /src/android/app/src/main/res
COPY web/ ./
COPY --from=web-engine /src/web/engine-files engine-files
RUN npm run build

# Caddy with the page in /srv. Its Caddyfile: compose's (its own TLS for $DOMAIN, /ws to the server) by default;
# behind an ingress that routes /ws and /health itself, the static one on :8080 (Caddyfile.static: run with
# `caddy run --config /etc/caddy/Caddyfile.static --adapter caddyfile`).
FROM caddy:2.8 AS site
# Without the binary's file capability (to bind ports below 1024), which a container without capabilities cannot run:
# a copy has none.
RUN cp /usr/bin/caddy /usr/bin/caddy.new && mv /usr/bin/caddy.new /usr/bin/caddy
COPY server/Caddyfile server/Caddyfile.static /etc/caddy/
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

# The engine needs the C++ runtime of the system it was built on: the same Ubuntu, with the JRE above; and ONNX
# Runtime where it was linked from (its run path), and the network its bots play (BOT_MODEL: the server checks at start
# that it plays, else its bots play tile efficiency alone).
FROM ubuntu:26.04 AS runtime
COPY --from=jre /opt/jre /opt/jre
RUN useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin mahjong
COPY --from=engine /opt/onnxruntime/lib /opt/onnxruntime/lib
COPY --from=model /model/actor.onnx /opt/mahjong/actor.onnx
COPY --from=engine /src/cpp/build/mahjong /opt/mahjong/mahjong
COPY --from=server /src/server/build/install/mahjong-server /opt/mahjong/server
ENV JAVA_HOME=/opt/jre \
    PATH=/opt/jre/bin:$PATH \
    MAHJONG_BINARY=/opt/mahjong/mahjong \
    BOT_MODEL=/opt/mahjong/actor.onnx \
    JAVA_OPTS="-Xmx128m -Xss512k -XX:+UseSerialGC -XX:ReservedCodeCacheSize=32m -XX:MaxMetaspaceSize=64m -XX:TieredStopAtLevel=1 -XX:+ExitOnOutOfMemoryError"
USER mahjong
EXPOSE 8080
ENTRYPOINT ["/opt/mahjong/server/bin/mahjong-server"]
