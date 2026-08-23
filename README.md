# DR1ommer

Utility for gathering all episodes for a select subset of podcasts from dr.dk and generating RSS feeds.

Usage:
```bash
$ java -jar ommer.jar <config>
```
where `<config>` is a configuration file like the `config.example` file in the root of this repository.

Note that you will need to fill in the API key in the config file. This can easily be obtained by enabling developer
tools in Firefox and inspecting the requests on dr.dk/lyd.

The list of podcasts is discovered from the DR API on every run, so it needs no maintenance.

Feed slugs are pinned in `slugs.json` in the output directory, which maps DR's stable series id to the slug we publish
under. A series is pinned the first time it is seen, using the slug DR uses at that moment; after that DR can rename it
freely and our feed URL stays put. Edit the file by hand to publish a series under a different slug. It is written
before any feeds are generated, so an interrupted run still records its pins, and it can be reconstructed from the
already published feeds if it is lost. Keep it in version control along with the rest of the output directory.

To see what would be generated without generating anything, run:

```bash
$ java -jar ommer.jar <config> --list
```
