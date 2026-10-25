#!/usr/bin/env bash
# Uploads build/staging-deploy to Maven Central as ONE deployment.
#
# The runtime, the generator and the Android adapters are published into the
# staging folder first (publishAllPublicationsToStagingRepository, signed), so
# this uploads all three or nothing: the Central Portal validates a deployment
# as a whole, and one that fails validation publishes nothing.
#
# Needs CENTRAL_USERNAME and CENTRAL_PASSWORD, a Central Portal user token.
# DRY_RUN=1 builds and checks the bundle without uploading it.
set -euo pipefail
cd "$(dirname "$0")/.."

STAGING="${LOCALAZE_STAGING_DIR:-build/staging-deploy}"
BUNDLE="build/central-bundle.zip"
API="https://central.sonatype.com/api/v1/publisher"

[ -d "$STAGING/dev/sharpy/localaze" ] || { echo "Nothing staged in $STAGING. Publish to the staging repository first." >&2; exit 1; }

# Every file Central receives needs a signature and checksums. Refuse here,
# with the file named, rather than after an upload.
missing=0
while IFS= read -r file; do
  for extra in asc md5 sha1; do
    [ -f "$file.$extra" ] || { echo "missing $file.$extra" >&2; missing=1; }
  done
done < <(find "$STAGING" -type f \( -name '*.jar' -o -name '*.aar' -o -name '*.pom' -o -name '*.module' \))
[ "$missing" = 0 ] || { echo "Not uploading: the bundle is incomplete. Is LOCALAZE_SIGNING_KEY set?" >&2; exit 1; }

for artifact in localaze localaze-generator localaze-android; do
  [ -d "$STAGING/dev/sharpy/localaze/$artifact" ] || { echo "Not uploading: $artifact is not staged." >&2; exit 1; }
done

rm -f "$BUNDLE"
(cd "$STAGING" && zip -qr - . -x '*maven-metadata.xml*') > "$BUNDLE"
echo "bundle: $BUNDLE, $(unzip -l "$BUNDLE" | tail -1 | awk '{print $2}') files"

if [ "${DRY_RUN:-0}" = 1 ]; then
  unzip -l "$BUNDLE" | awk 'NR>3 {print $4}' | grep -v '^$' | sed 's/^/  /'
  echo "DRY_RUN: not uploaded"
  exit 0
fi

: "${CENTRAL_USERNAME:?CENTRAL_USERNAME is not set}"
: "${CENTRAL_PASSWORD:?CENTRAL_PASSWORD is not set}"
AUTH="Authorization: Bearer $(printf '%s:%s' "$CENTRAL_USERNAME" "$CENTRAL_PASSWORD" | base64 | tr -d '\n')"

VERSION="$(ls "$STAGING/dev/sharpy/localaze/localaze" | grep -v maven-metadata | head -1)"
ID="$(curl -sS --fail-with-body -H "$AUTH" \
  -F "bundle=@$BUNDLE" \
  "$API/upload?name=localaze-$VERSION&publishingType=AUTOMATIC")"
echo "deployment $ID: https://central.sonatype.com/publishing/deployments"

# Validation takes a minute or two. Publishing after it can take up to about
# thirty minutes to show in search, which this does not wait for: PUBLISHING
# means Central accepted it and nothing more can go wrong on this side.
for _ in $(seq 1 60); do
  state="$(curl -sS --fail-with-body -X POST -H "$AUTH" "$API/status?id=$ID")"
  phase="$(printf '%s' "$state" | python3 -c 'import json,sys; print(json.load(sys.stdin)["deploymentState"])')"
  case "$phase" in
    PUBLISHING|PUBLISHED)
      echo "accepted: $phase. dev.sharpy.localaze $VERSION appears on Maven Central within about thirty minutes."
      exit 0 ;;
    FAILED)
      echo "Central refused the deployment; nothing was published:" >&2
      printf '%s' "$state" | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin).get("errors", {}), indent=2, ensure_ascii=False))' >&2
      exit 1 ;;
    *) sleep 10 ;;
  esac
done
echo "Central is still validating deployment $ID after ten minutes. Check the link above." >&2
exit 1
