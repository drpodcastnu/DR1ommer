#!/bin/bash
replacements="
s/^sara-og-monopolet-podcast$/sara-og-monopolet/g;
s/^mads-monopolet-podcast$/sara-og-monopolet/g;
s/^hjernekassen-paa-p1$/hjernekassen/g;
s/^hjernekassen-pa-p1$/hjernekassen/g;
s/^moerklagt-agent-samsam$/moerklagt/g;
"

curl 'https://api.dr.dk/radio/v2/series?limit=10000' --compressed -H 'User-Agent: Mozilla/5.0 (X11; Linux x86_64; rv:109.0) Gecko/20100101 Firefox/119.0' -H 'Accept: application/json' -H 'Accept-Encoding: gzip, deflate, br' -H 'Referer: https://www.dr.dk/' -H 'x-apikey: 6Wkh8s98Afx1ZAaTT4FuWODTmvWGDPpR' > series.json

echo -n > found_shows.txt
echo -n '[' > new.json
first=true
IFS=$'\n'
for show in $(jq -c '.items[] | { title: .title, url: .podcastUrl, urn: .id, slug: .slug}' series.json); do
    TITLE=$(echo "$show" | jq -r '.title' -)
    URL=$(echo "$show" | jq -r '.url' -)
    if [ "$URL" = "null" ]; then
      # Wasn't present for all shows
      SLUG=$(echo "$show" | jq -r '.slug' - | sed 's/-[0-9]*$//')
    else
      SLUG=$(echo "$URL" | grep -o '[^/]*$')
    fi

    SLUG=$(echo "$SLUG" | sed "$replacements")
    echo "$TITLE ($SLUG)" >> found_shows.txt
    if [ "$first" = "true" ]; then
      first=false
      echo >> new.json
    else
      echo "," >> new.json
    fi
    echo -n "$show" | jq -j "{ urn: .urn, slug: \"$SLUG\" }" >> new.json
done
unset IFS
echo ']' >> new.json

jq --slurpfile new new.json '.podcasts = $new[0]' ../ommer/src/main/resources/podcasts.json > podcasts.json
mv podcasts.json ../ommer/src/main/resources/podcasts.json