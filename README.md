# Oarlock — offline library for when the internet is gone

Oarlock is an Android app that starts as an empty shell. You download only the packs you want, while you still have internet, and everything works afterward with no signal.

## Features
- **Emergency Download**: hold the red button for 2 seconds and Oarlock downloads your essentials in priority order (medical first, then survival, maps, books, Wikipedia). It keeps going with the screen off, resumes after interruptions, and skips anything that won't fit.
- **Storage at a glance**: every download screen shows free space, what Oarlock uses, what's queued and what other apps use, so you can free up room or download less.
- **Wikipedia**: the full English Wikipedia (about 27 GB) straight from Wikimedia's multistream dump. Articles open in a fraction of a second without unpacking the file. Optional **Optimize** step shrinks it by about 40%.
- **Kiwix packs**: WikEM, WikiMed, Army manuals, iFixit, Appropedia, Wiktionary, LibreTexts, topic image packs and more, with full-text search.
- **Maps**: an offline world map plus North America at your choice of detail (4–17 GB), or any area you outline. GPS works with no signal.
- **Books**: about 250 hand-picked public-domain books in 13 genre collections, including scripture from the ten largest religions and Unitarian Universalist heritage, plus the full Project Gutenberg catalog (70,000+ titles) to browse by genre.
- **Field Guide**: built-in first aid and survival basics, no download needed.
- **Themes**: OLED Amber (default), Night Vision Red, Terminal Green, Arctic Blue, OLED Graphite, Dusk Amber, Paper, Daylight.

## Build
```
./gradlew assembleRelease   # needs oarlock-release.jks in the project root (see app/build.gradle.kts)
```
`tools/` holds the scripts that generate `app/src/main/assets/catalog.json` (Kiwix catalog, curated book lists, measured map sizes).

## Sources and licenses
Wikipedia & Wikimedia (CC BY-SA), Kiwix/openZIM, OpenStreetMap via Protomaps (ODbL), Project Gutenberg and Internet Archive (public domain), MapLibre GL (BSD).
