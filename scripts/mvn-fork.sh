#!/usr/bin/env bash
# Run Maven for this fork against its own local repository.
#
# The fork and the thesis' vendored Endive both install as
# run.endive:*:999-SNAPSHOT. The thesis benchmark harness resolves Endive from
# ~/.m2/repository, so a plain `mvn install` here would silently put fork
# builds under its measurements. This wrapper keeps every fork artifact in
# ~/.m2/repository-endive-fork (override with ENDIVE_FORK_M2).
#
# On first use the fork repository is seeded from ~/.m2/repository without
# run/endive, so third-party dependencies need not be downloaded again.
#
# Usage: scripts/mvn-fork.sh [maven args...]
#   e.g. scripts/mvn-fork.sh -Dquickly -pl compiler -am install
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" &> /dev/null && pwd)
ROOT=$(cd -- "${SCRIPT_DIR}/.." && pwd)
FORK_M2="${ENDIVE_FORK_M2:-${HOME}/.m2/repository-endive-fork}"
SHARED_M2="${HOME}/.m2/repository"

reject_repo_override() {
  echo "mvn-fork.sh: do not set maven.repo.local ($1); set ENDIVE_FORK_M2 instead" >&2
  exit 2
}

# Maven also takes a property as the argument after -D or --define, and Maven 3.9+ prepends
# MAVEN_ARGS to the command line, so check every spelling that could replace the repository.
check_args() {
  local previous=""
  for arg in "$@"; do
    case "${previous}" in
      -D|--define)
        case "${arg}" in
          maven.repo.local|maven.repo.local=*) reject_repo_override "${previous} ${arg}" ;;
        esac
        ;;
    esac
    case "${arg}" in
      -Dmaven.repo.local|-Dmaven.repo.local=*|--define=maven.repo.local|--define=maven.repo.local=*)
        reject_repo_override "${arg}"
        ;;
    esac
    previous="${arg}"
  done
}

check_args "$@"
# shellcheck disable=SC2086 # MAVEN_ARGS is split by Maven too
check_args ${MAVEN_ARGS:-}
case " ${MAVEN_OPTS:-} " in
  *" -Dmaven.repo.local"*) reject_repo_override "MAVEN_OPTS" ;;
esac

if [ "$(realpath -m "${FORK_M2}")" = "$(realpath -m "${SHARED_M2}")" ]; then
  echo "mvn-fork.sh: ENDIVE_FORK_M2 must not be the shared ${SHARED_M2}" >&2
  exit 2
fi

if [ ! -d "${FORK_M2}" ]; then
  mkdir -p "${FORK_M2}"
  if [ -d "${SHARED_M2}" ]; then
    echo "mvn-fork.sh: seeding ${FORK_M2} from ${SHARED_M2} (without run/endive)" >&2
    (cd "${SHARED_M2}" && tar --exclude=./run/endive -cf - .) | (cd "${FORK_M2}" && tar -xf -)
  fi
fi

exec "${MVN:-${ROOT}/mvnw}" -f "${ROOT}/pom.xml" -Dmaven.repo.local="${FORK_M2}" "$@"
