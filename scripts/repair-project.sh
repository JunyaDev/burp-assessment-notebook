#!/usr/bin/env bash
# Check an Assessment Notebook project directory for inconsistencies and, with
# --apply, repair it. Runs outside Burp, on the project's files.
#
#   scripts/repair-project.sh <project-directory>            report only (dry run)
#   scripts/repair-project.sh <project-directory> --apply    fix, rebuild documents
#   scripts/repair-project.sh --help                         all options
#
# The work is done by com.assessmentnotebook.repair.RepairTool in the extension
# jar, so the repair uses the same model and document generator as the
# extension. The jar is (re)built first if it is missing or older than the code.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
jar="$here/build/libs/assessment-notebook.jar"

if [ ! -f "$jar" ] || [ -n "$(find "$here/src/main" -newer "$jar" -print -quit)" ]; then
  echo "==> building the extension jar" >&2
  "$here/scripts/build-jar.sh" >&2
fi

exec java -cp "$jar" com.assessmentnotebook.repair.RepairTool "$@"
