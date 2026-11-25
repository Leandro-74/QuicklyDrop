# Project name

> Decision: **QuicklyDrop**. The name applies to the first release and the repository.

## 1. Situation

The project started as `EasyDrop`, but the name conflicts with an existing project on
PyPI/GitHub (`TheLastGimbus/easydrop`, an AirDrop CLI for Linux). The conflict is about
search and identity, not code. Before the first release, the project was renamed to
`QuicklyDrop`.

Current identifiers:

- Repository: `Leandro-74/QuicklyDrop`.
- `applicationId`: `dev.leandro.quicklydrop` (product), `.lab` and `.diag`.
- Module namespace: `dev.leandro.quicklydrop`.
- Wire identifier: `quicklydrop/1`.

The wire identifier is stable from the first release and does not change with the
commercial name. Changing the wire identifier requires a new protocol version.

## 2. What to check before a public release

- PyPI, npm, GitHub, F-Droid, Google Play and domain registration.
- Trademark and package name on F-Droid.
- Availability of `dev.leandro.quicklydrop`.
- Conflict with "Quick Drop" (Samsung) and "Quick Share" (Google) in search.

## 3. What does not change

- Module namespace.
- Wire identifier: `quicklydrop/1`.
- NFC payload and protocol format.

## 4. Migration if the name changes again

1. Display name and icon.
2. `applicationId` and namespace.
3. Repository name and artifacts.
4. F-Droid metadata.
5. README and screenshots.
6. Wire identifier only in a new protocol version.
