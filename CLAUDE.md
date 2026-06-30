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

- Coordinates and dimensions are normalized to millimetres at parse time by **all three** parsers:
  `ExcellonParser` (drill hits, slots, tool diameters), `GerberParser` (operation coordinates,
  aperture sizes), and `Ipc356Parser` (netlist test points, conductors, hole diameters — from the
  file's `CUST` 0.0001 inch or `SI` 0.001 mm grid). The file's native unit (inch/mm) is consumed
  during parsing and not retained.
- After parsing, `getUnit()` returns `Unit.MM` on `DrillDocument`, `GerberDocument`, and
  `Ipc356Document`, so all parsed geometry can be treated as mm with no conversion — drill holes,
  Gerber flashes, and netlist test points share one coordinate space (this is what lets
  `DrillGerberAlignment` correlate them directly).
