#!/usr/bin/env sh
# Maintainer build: recompiles the patched class from source, rewrites the jar,
# verifies it, runs the tests, and refreshes the shipped prebuilt classes.
#
# Requires a JDK. End users do NOT run this; they run Patch-Windows.bat or
# Patch-Linux.sh, which use the prebuilt classes in ../classes/.
#
# Usage: ./build.sh [original.jar] [output.jar]
set -eu

here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
original=${1:-../put-jar-here/lycanitesmobs-1.12.2-2.0.8.10.jar}
output=${2:-../patched/lycanitesmobs-1.12.2-2.0.8.10.jar}

cd "$here"
mkdir -p build/tool
javac -d build/tool tools/BuildTool.java
java -cp build/tool BuildTool all "$original" "$output"

# Keep the prebuilt classes that the user-facing patchers copy in sync with source.
mkdir -p ../classes
cp -r build/classes/com ../classes/
echo "refreshed ../classes/com/lycanitesmobs/core/pets/"
