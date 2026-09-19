#!/usr/bin/env bash
# Runs the live provider tests (app/src/androidTest) on the connected Android device.
#
# Usage:  scripts/live-tests.sh [extra gradle args]
#         ANDROID_SERIAL=100.115.8.86:43761 scripts/live-tests.sh   # pick a specific device
#
# Keys come from .env at the repo root (see .env.example); tests for providers whose key is
# missing are skipped. Key values are never printed.
set -uo pipefail
cd "$(dirname "$0")/.."

if [[ ! -f .env ]]; then
    echo "error: .env not found at $(pwd)/.env" >&2
    echo "       cp .env.example .env  and fill in the provider API keys, then re-run." >&2
    exit 1
fi

if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    echo "Target device: $ANDROID_SERIAL (ANDROID_SERIAL)"
    export ANDROID_SERIAL
else
    echo "Target device: first device listed by adb (set ANDROID_SERIAL to choose)"
fi

# leaveApksInstalledAfterRun: by default AGP UNINSTALLS the app (and its data: API keys,
# settings, downloaded models) when the run ends. Never do that to the user's install.
./gradlew :app:connectedDebugAndroidTest \
    -Pkotlin.compiler.execution.strategy=in-process \
    -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
    --console=plain "$@"
status=$?

report_dir="app/build/reports/androidTests/connected"
echo
echo "Reports:"
if [[ -d "$report_dir" ]]; then
    find "$report_dir" -name 'index.html' | sed 's/^/  HTML: /'
else
    echo "  (no report directory at $report_dir)"
fi
xml_dir="app/build/outputs/androidTest-results/connected"
if [[ -d "$xml_dir" ]]; then
    find "$xml_dir" -name '*.xml' | sed 's/^/  XML:  /'
fi
exit $status
