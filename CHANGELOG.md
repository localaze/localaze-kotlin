# Changelog

Versions follow `contract/RELEASING.md`: the Dart, Swift and Kotlin packages release together, at the same version.

## Unreleased

First public release. The first version is 0.2.0 on every platform, because `localaze` on pub.dev already has an unrelated 0.1.0.

- `dev.sharpy.localaze:localaze`: the runtime. Fetches, caches and applies catalogs under an update policy and a names-and-types signature check, renders messages, and never throws.
- `dev.sharpy.localaze:localaze-generator`: a self-contained jar that generates typed accessors and catalogs from a published release, per `contract/generator-contract.md`.
