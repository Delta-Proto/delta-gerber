# CLAUDE.md

## Project

Delta Gerber — Java Gerber/NC drill file parser with SVG export.

## Build & Test

```bash
mvn clean test                # Run all tests
mvn test -Dtest=ClassName     # Run a specific test class
```

## Deploy

See [DEPLOY.md](DEPLOY.md) for release instructions. The GPG passphrase is in `.mvn-gpg-passphrase`.

```bash
mvn clean deploy -Prelease -Dgpg.passphrase=$(cat .mvn-gpg-passphrase)
```

## Key Conventions

- Coordinates and dimensions are normalized to millimetres at parse time by **both** parsers:
  `ExcellonParser` (drill hits, slots, tool diameters) and `GerberParser` (operation coordinates,
  aperture sizes). The file's native unit (inch/mm) is consumed during parsing and not retained.
- After parsing, `getUnit()` returns `Unit.MM` on both `DrillDocument` and `GerberDocument`, so all
  parsed geometry can be treated as mm with no conversion — drill holes and Gerber flashes share one
  coordinate space (this is what lets `DrillGerberAlignment` correlate them directly).
