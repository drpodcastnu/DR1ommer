# DR1ommer

Utility for gathering all episodes for a select subset of podcasts from dr.dk and generating RSS feeds.

Usage:
```bash
$ java -jar ommer.jar <output directory>
```
where `<output directory>` is where the feeds and the index page are written. There is nothing else to configure; the
API URL and key are compiled in. The key is not a secret, it is the one dr.dk/lyd hands to every browser that loads
the page.

The jar is self-contained and published on every tagged release, so a server needs nothing but the jar itself:
```bash
$ curl -LO https://github.com/drpodcastnu/DR1ommer/releases/latest/download/ommer.jar
```
To cut a release, push a `v*` tag; `.github/workflows/release.yml` builds the jar and attaches it.

If DR ever rotates the key, get the new one by enabling developer tools in Firefox and inspecting the requests on
dr.dk/lyd, then update `apiKey` in `Client.kt` and cut a release.

The list of podcasts is discovered from the DR API on every run, so it needs no maintenance.

A run writes a complete, self-contained site: the feeds, the index page, and the stylesheet and
images it references. Nothing needs to be supplied alongside it.

Feed slugs are pinned in `slugs.json` in the output directory, which maps DR's stable series id to the slug we publish
under. A series is pinned the first time it is seen, using the slug DR uses at that moment; after that DR can rename it
freely and our feed URL stays put. Edit the file by hand to publish a series under a different slug. It is written
before any feeds are generated, so an interrupted run still records its pins, and it can be reconstructed from the
already published feeds if it is lost. Keep it in version control along with the rest of the output directory.

To see what would be generated without generating anything, run:

```bash
$ java -jar ommer.jar --list <output directory>
```
