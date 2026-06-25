# Deploy to Maven Central

Quick reference for deploying a release. See [RELEASING.md](RELEASING.md) for prerequisites and setup.

## Steps

### 1. Update Version

Update the version in `pom.xml` (remove `-SNAPSHOT` if present):

```xml
<version>X.Y.Z</version>
```

### 2. Build and Deploy

```bash
mvn clean deploy -Prelease -Dgpg.passphrase=$(cat .mvn-gpg-passphrase)
```

The GPG passphrase is stored in `.mvn-gpg-passphrase` (gitignored).

### 3. Verify the Release

Check [Maven Central](https://central.sonatype.com/artifact/com.deltaproto/delta-gerber) for the published artifact.

### 4. Tag the Release

```bash
git tag -a vX.Y.Z -m "Release version X.Y.Z"
git push origin vX.Y.Z
```

### 5. Create GitHub Release

Attach the standalone fat jar (`jar-with-dependencies`) so it can be downloaded
directly from the release page, as done up to 1.1.3 but missing since 1.1.4. The
`maven-assembly-plugin` produces it during the build at
`target/delta-gerber-X.Y.Z-jar-with-dependencies.jar`:

```bash
gh release create vX.Y.Z --title "Release X.Y.Z" --notes "# Changes

- Description of changes" \
  target/delta-gerber-X.Y.Z-jar-with-dependencies.jar
```

If the release already exists, attach the jar to it instead:

```bash
gh release upload vX.Y.Z target/delta-gerber-X.Y.Z-jar-with-dependencies.jar
```

### 6. Prepare Next Development Version

Update `pom.xml` to the next SNAPSHOT version (e.g. `X.Y.Z+1-SNAPSHOT`).
