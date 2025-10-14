#!/bin/bash

set -euo pipefail

# Fixes to normalize show slugs
NORMALIZE_FILTER=$(cat <<'JQ'
def replace_slug:
  sub("sara-og-monopolet-podcast"; "sara-og-monopolet"; "g")
  | sub("mads-monopolet-podcast"; "sara-og-monopolet"; "g")
  | sub("hjernekassen-paa-p1"; "hjernekassen"; "g")
  | sub("hjernekassen-pa-p1"; "hjernekassen"; "g")
  | sub("moerklagt-agent-samsam"; "moerklagt"; "g");

def derive_slug($url; $slug):
  if $url != null and $url != "" then
    ($url | split("/") | last | replace_slug)
  elif $slug != null then
    ($slug | sub("-[0-9]+$"; "") | replace_slug)
  else
    null
  end;

def derive_umbrella($umbrella):
  if $umbrella != null then
    (
      ($umbrella.presentationUrl // "") as $url
      | if $url != "" then
          ($url | split("/") | last)
        else
          $umbrella.slug
        end
    )
    | select(. != null)
    | sub("-[0-9]+$"; "")
    | replace_slug
  else
    null
  end;

.items |= map(
  . + {
    derivedSlug: derive_slug(.podcastUrl; .slug),
    umbrellaSlug: derive_umbrella(.umbrella)
  }
)
JQ
)

RAW_SERIES=updater/series.raw.json
SERIES=updater/series.json

# Download and fix the original podcasts feed
curl 'https://api.dr.dk/radio/v2/series?limit=10000' \
  --compressed \
  -H 'User-Agent: Mozilla/5.0 (X11; Linux x86_64; rv:109.0) Gecko/20100101 Firefox/119.0' \
  -H 'Accept: application/json' \
  -H 'Accept-Encoding: gzip, deflate, br' \
  -H 'Referer: https://www.dr.dk/' \
  -H 'x-apikey: 6Wkh8s98Afx1ZAaTT4FuWODTmvWGDPpR' \
  > "$RAW_SERIES"

jq "$NORMALIZE_FILTER" "$RAW_SERIES" > "$SERIES"

tmp_dir=$(mktemp -d)
trap 'rm -rf "$tmp_dir"' EXIT

items_json="$tmp_dir/items.json"
originals_json="$tmp_dir/originals.json"
umbrellas_json="$tmp_dir/umbrellas.json"

# Output shows for debugging
jq '
  .items[]
  | select(.type == "Series")
  | "\(.title) (\(.derivedSlug // ""))"
' "$SERIES" > updater/found_shows.txt

# Extract all shows 1:1
jq '
  [
    .items[]
    | select(.type == "Series" and .derivedSlug != null)
    | {
        slug: .derivedSlug,
        urn: .id,
        umbrella: .umbrellaSlug
      }
  ]
' "$SERIES" > "$items_json"

jq '[ .[] | { slug, urns: [.urn] } ]' "$items_json" > "$originals_json"

# Find all shows that are part of an umbrella show (meta shows)
jq '
  [ .[] | select(.umbrella != null) ]
  | group_by(.umbrella)
  | map(select(length > 1))
  | map({
      slug: .[0].umbrella,
      urns: (map(.urn) | unique | sort)
    })
' "$items_json" > "$umbrellas_json"

# Merge all shows, meta and individual shows, into one final
jq -s '
  map(.[])
  | group_by(.slug)
  | map({
      slug: .[0].slug,
      urns: (map(.urns) | add | unique | sort)
    })
  | sort_by(.slug)
' "$originals_json" "$umbrellas_json" > updater/new.json

# Merge podcasts.template.json and new.json into podcasts.json
jq --slurpfile podcasts updater/new.json '.podcasts = $podcasts[0]' podcasts.template.json > ommer/src/main/resources/podcasts.json
