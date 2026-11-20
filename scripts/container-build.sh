#!/bin/sh
set -eu
cd /app
requested_tasks=" $* "
build_succeeded=0
collect_outputs() {
    mkdir -p /output
    if [ "$build_succeeded" = 1 ]; then
        case "$requested_tasks" in
            *assembleDebug*|*assemble\ *|*\ build\ *) find app/build/outputs/apk/debug -name '*.apk' -exec cp {} /output/ \; 2>/dev/null || true ;;
        esac
        case "$requested_tasks" in
            *assembleRelease*|*assemble\ *|*\ build\ *) find app/build/outputs/apk/release -name '*.apk' -exec cp {} /output/ \; 2>/dev/null || true ;;
        esac
    fi
    if [ -d app/build/reports ]; then cp -r app/build/reports /output/; fi
}
trap collect_outputs EXIT
# Windows checkout permissions and local SDK paths must not leak into the container.
if [ "${1:-}" = "./gradlew" ]; then shift; set -- sh ./gradlew "$@"; fi
"$@"
build_succeeded=1
