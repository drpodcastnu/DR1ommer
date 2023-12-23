# DR1ommer

Utility for gathering all episodes for a select subset of podcasts from dr.dk and generating RSS feeds.

Usage:
```bash
$ java -jar ommer.jar <config>
```
where `<config>` is a configuration file like the `config.example` file in the root of this repository.

Note that you will need to fill in the API key in the config file. This can easily be obtained by enabling developer
tools in Firefox and inspecting the requests on dr.dk/lyd.