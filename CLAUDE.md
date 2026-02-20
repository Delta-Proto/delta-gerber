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

- All drill file coordinates and tool diameters are normalized to mm at parse time in `ExcellonParser`.
- Gerber files store coordinates in their native unit (check `GerberDocument.getUnit()`).
- DRC code uses `doc.getUnit().toMm()` to convert to mm for distance calculations.
