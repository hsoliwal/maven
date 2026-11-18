#!/usr/bin/env bash
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

# -----------------------------------------------------------------------------
# Checks the LICENSE of a -Pplugins distribution against its boot repository (repository/).
#
# Usage: bash check-boot-repository-licences.sh <unpacked distribution directory>
#
# A jar in repository/ passes when LICENSE lists it ("- repository/<path>: ...") or when the nearest POM in its
# parent chain that declares <licenses> declares the Apache License, Version 2.0. A LICENSE entry passes when its
# jar is in repository/ and repository/<artifactId>.license is not empty. One record per jar and per entry:
#   JAR <path> LISTED|APACHE-2.0|UNLISTED <declared licenses>
#   ENTRY <path> OK|NO_JAR|NO_LICENSE_FILE
#   RESULT pass|fail jars=<n> listed=<n> apache=<n> unlisted=<n> entries=<n> bad_entries=<n>
# Exits 1 on any UNLISTED jar or bad entry.
# -----------------------------------------------------------------------------
set -euo pipefail

dist=${1:?usage: check-boot-repository-licences.sh <unpacked distribution directory>}
repo=$dist/repository
[ -d "$repo" ] && [ -f "$dist/LICENSE" ] || { echo "RESULT fail no repository/ or LICENSE in $dist"; exit 1; }

entries=$(tr -d '\r' < "$dist/LICENSE" | sed -n 's|^- repository/\([^:]*\): .*|\1|p' | sort -u)

# nearest declared <licenses> of a POM, following <parent> inside repository/; prints "name url" per license
declared_licenses() {
  local pom=$1 depth=0 block parent g a v
  while [ -f "$pom" ] && [ "$depth" -lt 20 ]; do
    block=$(tr -d '\r' < "$pom" | sed -n '/<licenses>/,/<\/licenses>/p')
    if [ -n "$block" ]; then
      printf '%s\n' "$block" | tr -d '\n' | awk '{ gsub(/<\/license>/, "&\n"); print }' | awk '{
        n = ""; u = ""
        if (match($0, /<name>[^<]*<\/name>/)) n = substr($0, RSTART + 6, RLENGTH - 13)
        if (match($0, /<url>[^<]*<\/url>/)) u = substr($0, RSTART + 5, RLENGTH - 11)
        if (n != "" || u != "") print n " " u }'
      return
    fi
    parent=$(tr -d '\r' < "$pom" | sed -n '/<parent>/,/<\/parent>/p' | tr -d ' \t\n')
    g=$(printf '%s' "$parent" | sed -n 's|.*<groupId>\([^<]*\)</groupId>.*|\1|p')
    a=$(printf '%s' "$parent" | sed -n 's|.*<artifactId>\([^<]*\)</artifactId>.*|\1|p')
    v=$(printf '%s' "$parent" | sed -n 's|.*<version>\([^<]*\)</version>.*|\1|p')
    [ -n "$g" ] && [ -n "$a" ] && [ -n "$v" ] || break
    pom=$repo/${g//.//}/$a/$v/$a-$v.pom
    depth=$((depth + 1))
  done
  echo "UNDECLARED"
}

jars=0 listed=0 apache=0 unlisted=0 bad=0 nentries=0
while IFS= read -r path; do
  jars=$((jars + 1))
  if printf '%s\n' "$entries" | grep -qxF "$path"; then
    listed=$((listed + 1)); echo "JAR $path LISTED"; continue
  fi
  dir=${path%/*}; version=${dir##*/}; artifact=${dir%/*}; artifact=${artifact##*/}
  lic=$(declared_licenses "$repo/$dir/$artifact-$version.pom" | paste -sd ';' -)
  if printf '%s' "$lic" | grep -qiE 'apache\.org/licenses/LICENSE-2\.0|Apache[- ](Software )?License,? *(Version )?2\.0|^Apache-2\.0|;Apache-2\.0'; then
    apache=$((apache + 1)); echo "JAR $path APACHE-2.0"
  else
    unlisted=$((unlisted + 1)); echo "JAR $path UNLISTED $lic"
  fi
done < <(cd "$repo" && find . -type f -name '*.jar' | sed 's|^\./||' | sort)

while IFS= read -r path; do
  [ -n "$path" ] || continue
  nentries=$((nentries + 1))
  artifact=$(printf '%s' "$path" | awk -F/ '{print $(NF-2)}')
  if [ ! -f "$repo/$path" ]; then
    bad=$((bad + 1)); echo "ENTRY $path NO_JAR"
  elif [ ! -s "$repo/$artifact.license" ]; then
    bad=$((bad + 1)); echo "ENTRY $path NO_LICENSE_FILE"
  else
    echo "ENTRY $path OK"
  fi
done <<< "$entries"

verdict=pass
[ "$unlisted" -eq 0 ] && [ "$bad" -eq 0 ] || verdict=fail
echo "RESULT $verdict jars=$jars listed=$listed apache=$apache unlisted=$unlisted entries=$nentries bad_entries=$bad"
[ "$verdict" = pass ]
