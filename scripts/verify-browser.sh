#!/bin/sh
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
: "${MPRP_BROWSER_BASE_URL:?Set the isolated local verification application URL}"
: "${MPRP_DEMO_ACCESS_PATH:?Set the private isolated demo credential file path}"
if [ "${MPRP_BROWSER_ISOLATED:-false}" != true ]; then
  echo 'Refusing browser mutations without MPRP_BROWSER_ISOLATED=true.' >&2
  exit 2
fi
exec ./mvnw -pl application -am test -Dtest=BrowserJourneyTest -Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.timeout=600 -Dsurefire.exitTimeout=10 "$@"
