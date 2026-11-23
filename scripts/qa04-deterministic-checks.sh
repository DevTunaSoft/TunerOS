#!/usr/bin/env bash
# QA-04 entry point. Keeps the device/CI wrapper separate from the fixture.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
exec bash "$HERE/../qa/fixtures/qa04-rearm/run_qa04_jvm_tests.sh" "$@"
