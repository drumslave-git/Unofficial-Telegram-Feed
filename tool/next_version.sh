#!/usr/bin/env bash
# The next release version from the conventional commits since the last tag vX.Y.Z.
#
#   tool/next_version.sh [--notes <file>] [--github-output <file>]
#
# Rules: a breaking change (`type!:` or a `BREAKING CHANGE:` footer) raises the major version,
# or the minor one while the major is 0; `feat` raises the minor; `fix` and `perf` raise the
# patch. Commits of other types (docs, chore, test, ci, refactor, ...) alone release nothing.
# Without any earlier tag the version is 0.1.0 and the notes list the commits since the
# upstream release the fork started from. Prints `release=true|false`, `version=X.Y.Z`,
# `tag=vX.Y.Z` and `previous=<last tag>` (also appended to --github-output) and writes the
# changelog to --notes.
set -euo pipefail

INITIAL=0.1.0
UPSTREAM_BASE=f2908b141   # the DrKLO/Telegram release commit the fork started from
notes=""
github_output=""
while [ $# -gt 0 ]; do
  case "$1" in
    --notes) notes="$2"; shift 2 ;;
    --github-output) github_output="$2"; shift 2 ;;
    *) echo "unknown argument $1" >&2; exit 2 ;;
  esac
done

last=$(git tag --merged HEAD --list 'v[0-9]*' | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | sort -V | tail -1 || true)

if [ -n "$last" ]; then
  range="$last..HEAD"
else
  range="$UPSTREAM_BASE..HEAD"
fi

bump=none   # none < patch < minor < major
breaking=""
features=""
fixes=""
raise() {
  case "$bump:$1" in
    none:*|patch:minor|patch:major|minor:major) bump=$1 ;;
  esac
}

if [ -z "$last" ] || [ "$(git rev-list -n 1 "$last")" != "$(git rev-parse HEAD)" ]; then
  while IFS= read -r -d $'\x1e' record || [ -n "$record" ]; do
    record="${record#$'\n'}"
    [ -n "$record" ] || continue
    hash="${record%%$'\x1f'*}"
    rest="${record#*$'\x1f'}"
    subject="${rest%%$'\x1f'*}"
    body="${rest#*$'\x1f'}"
    if [[ "$subject" =~ ^([a-z]+)(\(([^\)]*)\))?(!)?:[[:space:]]*(.*)$ ]]; then
      type="${BASH_REMATCH[1]}"
      scope="${BASH_REMATCH[3]}"
      bang="${BASH_REMATCH[4]}"
      description="${BASH_REMATCH[5]}"
    else
      continue
    fi
    line="- "
    [ -n "$scope" ] && line+="**$scope:** "
    line+="$description ($hash)"
    if [ -n "$bang" ] || printf '%s\n' "$body" | grep -Eq '^BREAKING[ -]CHANGE:'; then
      raise major; breaking+="$line"$'\n'
    elif [ "$type" = feat ]; then
      raise minor; features+="$line"$'\n'
    elif [ "$type" = fix ] || [ "$type" = perf ]; then
      raise patch; fixes+="$line"$'\n'
    fi
  done < <(git log "$range" --no-merges --format='%h%x1f%s%x1f%b%x1e')
fi

release=false
version=""
if [ -z "$last" ]; then
  release=true
  version=$INITIAL
elif [ "$bump" != none ]; then
  IFS=. read -r major minor patch <<< "${last#v}"
  case "$bump" in
    patch) patch=$((patch + 1)) ;;
    minor) minor=$((minor + 1)); patch=0 ;;
    major) if [ "$major" = 0 ]; then minor=$((minor + 1)); patch=0; else major=$((major + 1)); minor=0; patch=0; fi ;;
  esac
  release=true
  version="$major.$minor.$patch"
fi

if [ -n "$notes" ]; then
  {
    [ -n "$breaking" ] && printf '### Breaking changes\n%s\n' "$breaking"
    [ -n "$features" ] && printf '### Features\n%s\n' "$features"
    [ -n "$fixes" ] && printf '### Fixes\n%s\n' "$fixes"
    [ -z "$breaking$features$fixes" ] && printf 'Maintenance only.\n'
    true
  } > "$notes"
fi

out="release=$release
version=${version:-${last#v}}
tag=v${version:-${last#v}}
previous=$last"
printf '%s\n' "$out"
if [ -n "$github_output" ]; then
  printf '%s\n' "$out" >> "$github_output"
fi
