#!/bin/sh
# Entry point of the Warden image. It replaces itself with the JVM (exec), so the JVM is PID 1,
# receives SIGTERM itself and shuts down gracefully (REQ-AVAIL-002).
#
# WARDEN_KEYS_FILE names an environment file with the WARDEN_* key variables. The reference
# environment points it at a volume: the first instance to start generates the file there
# (KeyFileGenerator) and every instance reads it, so all share the same keys. It is used only when
# the keys are not set in the environment, which always wins. No value is ever printed.
set -eu

if [ -n "${WARDEN_KEYS_FILE:-}" ] && [ -z "${WARDEN_JWT_SIGNING_KEY:-}" ]; then
    if [ ! -e "$WARDEN_KEYS_FILE" ]; then
        java -Xmx64m -cp "/app/BOOT-INF/classes:/app/BOOT-INF/lib/*" \
            io.github.kaulinmindiola.warden.shared.crypto.KeyFileGenerator "$WARDEN_KEYS_FILE"
    fi
    if [ ! -r "$WARDEN_KEYS_FILE" ]; then
        echo "WARDEN_KEYS_FILE is set but the file cannot be read" >&2
        exit 1
    fi
    set -a
    . "$WARDEN_KEYS_FILE"
    set +a
fi

# The heap takes at most 60 % of the container's memory limit; the rest is left to metaspace,
# threads, direct buffers and Argon2 (ADR-010). JAVA_TOOL_OPTIONS adds options without rebuilding.
exec java -XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError org.springframework.boot.loader.launch.JarLauncher "$@"