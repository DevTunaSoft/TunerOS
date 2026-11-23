#!/usr/bin/env bash
# QA-24: rewrite one manifest entry after a scratch fixture mutation.
set -u

dir=$1
name=$2
hash=$(sha256sum "$dir/$name" | awk '{print $1}')
awk -F'  ' -v OFS='  ' -v n="$name" -v h="$hash" '$2 == n { $1 = h } { print }' \
  "$dir/sha256.txt" > "$dir/sha256.tmp" || exit 1
mv "$dir/sha256.tmp" "$dir/sha256.txt" || exit 1
