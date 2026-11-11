# Integration dependency targets

Use the explicit artifact versions below as integration targets. Do not name or depend on a modpack in dependency declarations/contracts, resolve a moving modpack branch during builds, use dynamic versions, or silently substitute upstream latest. Changes to these targets must be explicit and reflected in affected adapter contracts and evidence. The standing project baseline is Minecraft 1.21.1, NeoForge 21.1.252 and Java 21.

Core integrations and the extension inventory below are selected for separately planned integration modules, outside A10. Exact API feasibility, transitive libraries, compatible dependency combinations and runtime behavior remain to be verified before claiming support. No JAR was downloaded or checked by this metadata inventory. Separate integration mods remain the architecture; optional adapters must not make core Economy require the entire extension set.

## Core artifact targets

| Mod | Exact target artifact |
| --- | --- |
| Applied Energistics 2 | `appliedenergistics2-19.2.18.jar` |
| Sophisticated Backpacks | `sophisticatedbackpacks-1.21.1-3.26.12.2208.jar` |
| Sophisticated Core | `sophisticatedcore-1.21.1-1.5.14.2413.jar` |
| Sophisticated Storage | `sophisticatedstorage-1.21.1-1.6.7.2184.jar` |

## Extension artifact targets

These 33 extension entries extend the Sophisticated/AE2 integration scope; they are not already verified integrations. [Extension support contract](EXTENSIONS.md) defines item valuation, authorized inventory access and cross-mod boundaries. Distinct similarly named entries are retained as supplied; do not assume they are interchangeable or safe to co-install without feasibility review.

| Extension | Exact target artifact |
| --- | --- |
| AdvancedAE | `AdvancedAE-1.6.12-1.21.1.jar` |
| AE2 Import Export Card | `ae2importexportcard-1.21.1-1.9.1.jar` |
| AE2 JEI Integration | `ae2jeiintegration-1.2.1.jar` |
| AE2 Network Analyser | `AE2NetworkAnalyzer-1.21-2.1.5-neoforge.jar` |
| AE2 Overclocked | `ae2_overclocked-1.2.3-fix2-beta1-1.21.1Neoforge.jar` |
| AE2 Things [Forge] | `AE2-Things-1.4.2-beta.jar` |
| AE2 Tweaks | `AE2Tweaks-1.21.1-1.0.jar` |
| AE2Colonies (Applied Energistics 2 × MineColonies) | `ae2colonies-1.2.4.jar` |
| ae2helpers | `ae2helpers-1.0.1.jar` |
| AEInfinityBooster | `aeinfinitybooster-neoforge-1.21.1-1.0.0.58.jar` |
| Applied Create | `appliedcreate-1.21.1-1.1.7.jar` |
| Applied Dyenamics | `appdyenamics-1.21.1-1.0.2.jar` |
| Applied Energistics 2 Wireless Terminals | `ae2wtlib-19.5.1.jar` |
| Applied Experienced | `applied-experienced-1.21.1-1.3.2.jar` |
| Applied Flux | `AppliedFlux-1.21-2.1.6-neoforge.jar` |
| Applied Mekanistics | `Applied-Mekanistics-1.6.3.jar` |
| Applied Oritech | `applied_oritech-1.0.4+1.21.1.jar` |
| Applied Pneumatics | `appliedpneumatics-1.21.1-neoforge-1.0.9.jar` |
| AppliedE | `appliede-1.0.8-beta.jar` |
| Cable Facades | `cable_facades-1.21.1-NeoForge-2.1.4.jar` |
| Cablephobic | `cablephobic-1.1.0.jar` |
| Create: Applied Kinetics | `createappliedkinetics-1.5.4-1.21.1.jar` |
| Create: Sophisticated Backpacks Compat | `create_sophback_compat-1.0.jar` |
| ExtendedAE | `ExtendedAE-1.21-2.2.41-neoforge.jar` |
| MEGA Cells | `megacells-4.11.0.jar` |
| Rechiseled: AE2 | `rechiseledae2-neoforge-1.21-1.21.1-1.1.0.jar` |
| Rechiseled: Applied Energistics 2 | `rechiseledae-1.0.1-neoforge-mc1.21.1.jar` |
| Sophisticated Backpack Allthemodium Tier | `backpack_allthemodium_upgrade-2.4.jar` |
| Sophisticated Backpacks Create Integration | `sophisticatedbackpackscreateintegration-1.21.1-0.2.3.194.jar` |
| Sophisticated Item Actions | `sophisticateditemactions-1.21.1-0.5.17.435.jar` |
| Sophisticated Storage Allthemodium tier | `storage_allthemodium_upgrade-2.0.1.jar` |
| Sophisticated Storage Create Integration | `sophisticatedstoragecreateintegration-1.21.1-0.1.23.246.jar` |
| Transparent AE2 | `transparentae2-1.21.1-1.1.1.jar` |

## Later integration target

MineColonies: `minecolonies-1.1.1406-1.21.1-snapshot.jar` (later scope). AE2Colonies is included above as an AE2 extension; that does not select Economy colony banking, wages or taxes. The planned casino integration has no assigned artifact yet.

## Artifact metadata

Recorded 2026-11-08. These are exact version/distribution/checksum metadata, not verified downloaded binary hashes. Distribution identifiers and artifact URLs are retained for reproducible resolution; no modpack reference forms part of these dependencies.

### AdvancedAE

```toml
name = "AdvancedAE"
filename = "AdvancedAE-1.6.12-1.21.1.jar"
side = "both"

[download]
hash-format = "sha1"
hash = "9358ccfa5477c7ab1c5ffab6c831e105fe46ecc3"
mode = "metadata:curseforge"

[update]
[update.curseforge]
file-id = 8564177
project-id = 1084104
```

### AE2 Import Export Card

```toml
name = "AE2 Import Export Card"
filename = "ae2importexportcard-1.21.1-1.9.1.jar"
side = "both"

[download]
hash-format = "sha1"
hash = "1b4fdfe01206524912fea28b7dea9e9449673018"
mode = "metadata:curseforge"

[update]
[update.curseforge]
file-id = 8961599
project-id = 982512
```

### AE2 JEI Integration

```toml
name = "AE2 JEI Integration"
filename = "ae2jeiintegration-1.2.1.jar"
side = "both"

[download]
hash-format = "sha1"
hash = "e99898b3e9c32bf6ac757e7ad50247746622cdfc"
mode = "metadata:curseforge"

[update]
[update.curseforge]
file-id = 7727898
project-id = 1074338
```

### AE2 Network Analyser

```toml
name = "AE2 Network Analyser"
filename = "AE2NetworkAnalyzer-1.21-2.1.5-neoforge.jar"
side = "both"

[download]
hash-format = "sha1"
hash = "c6cb26a2031687d801bf1763dc0c75cd92f3715c"
mode = "metadata:curseforge"

[update]
[update.curseforge]
file-id = 7622554
project-id = 961856
```

### AE2 Overclocked

```toml
name = "AE2 Overclocked"
filename = "ae2_overclocked-1.2.3-fix2-beta1-1.21.1Neoforge.jar"
side = "both"

[download]
hash-format = "sha1"
hash = "ef7b424e7ca934a6afb7debd3fc4fac719b9ab0f"
mode = "metadata:curseforge"

[update]
[update.curseforge]
file-id = 7930483
project-id = 1472240
```

### AE2 Things [Forge]

```toml
name = "AE2 Things [Forge]"
filename = "AE2-Things-1.4.2-beta.jar"
side = "both"

[download]
hash-format = "sha1"
hash = "593ad26ae4143f529a63cc63b96c6430ebba67c3"
mode = "metadata:curseforge"

[update]
[update.curseforge]
file-id = 5637783
project-id = 609977
```

### AE2 Tweaks

```toml
name = "AE2 Tweaks"
filename = "AE2Tweaks-1.21.1-1.0.jar"
side = "both"

[download]
hash-format = "sha1"
hash = "5d534522accb076fbe2c52a3d953982f85fd4150"
mode = "metadata:curseforge"

[update]
[update.curseforge]
file-id = 6199502
project-id = 1201668
```

### AE2Colonies (Applied Energistics 2 × MineColonies)

```toml
name = "AE2Colonies (Applied Energistics 2 × MineColonies)"
filename = "ae2colonies-1.2.4.jar"
side = "both"

[download]
hash-format = "sha1"
hash = "74665965bc44ce1f12cefb78127cbd7bdb000262"
mode = "metadata:curseforge"

[update]
[update.curseforge]
file-id = 9253782
project-id = 1694075
```

### ae2helpers

```toml
name = "ae2helpers"
filename = "ae2helpers-1.0.1.jar"
side = "both"

[download]
hash-format = "sha1"
hash = "37005b1380af93013900f83140a70d95b4b48c70"
mode = "metadata:curseforge"

[update]
[update.curseforge]
file-id = 7634384
project-id = 1450496
```

### AEInfinityBooster

```toml
name = "AEInfinityBooster"
filename = "aeinfinitybooster-neoforge-1.21.1-1.0.0.58.jar"
side = "both"

[download]
hash-format = "sha1"
hash = "61756becbab62dd376378ceb74a3daa45b70562a"
mode = "metadata:curseforge"

[update]
[update.curseforge]
file-id = 8377914
project-id = 559313
```

### Applied Create

```toml
name = "Applied Create"
filename = "appliedcreate-1.21.1-1.1.7.jar"
side = "both"

[download]
hash-format = "sha1"
hash = "bcc3e3999c0130c08b36d35d3e2e077c48af280c"
mode = "metadata:curseforge"

[update]
[update.curseforge]
file-id = 8546920
project-id = 1474758
```

### Applied Dyenamics

```toml
name = "Applied Dyenamics"
filename = "appdyenamics-1.21.1-1.0.2.jar"
side = "both"

[download]
hash-format = "sha1"
hash = "ac7c7ab0152b7040d103beaf656fa2cd64d0f4c5"
mode = "metadata:curseforge"

[update]
[update.curseforge]
file-id = 7113261
project-id = 1364839
```

### Applied Energistics 2 Wireless Terminals

```toml
name = "Applied Energistics 2 Wireless Terminals"
filename = "ae2wtlib-19.5.1.jar"
side = "both"

[download]
hash-format = "sha1"
hash = "b1bee01393b15f1eb0f46d58862ef99234e1d337"
mode = "metadata:curseforge"

[update]
[update.curseforge]
file-id = 8450398
project-id = 459929
```

### Applied Energistics 2

```toml
name = "Applied Energistics 2"
filename = "appliedenergistics2-19.2.18.jar"
side = "both"

[download]
hash-format = "sha1"
hash = "bcd04bc7f181b2ffa314625f284c8eca6fee7fe4"
mode = "metadata:curseforge"

[update]
[update.curseforge]
file-id = 8992605
project-id = 223794
```

### Applied Experienced

```toml
name = "Applied Experienced"
filename = "applied-experienced-1.21.1-1.3.2.jar"
side = "both"

[download]
hash-format = "sha1"
hash = "751aceaca3309b5618e7ee435a133ec245180ea3"
mode = "metadata:curseforge"

[update]
[update.curseforge]
file-id = 7705498
project-id = 1157608
```

### Applied Flux

```toml
name = "Applied Flux"
filename = "AppliedFlux-1.21-2.1.6-neoforge.jar"
side = "both"

[download]
hash-format = "sha1"
hash = "ef223560c0e5f89ee675b4223123221dc4b06ab3"
mode = "metadata:curseforge"

[update]
[update.curseforge]
file-id = 8970797
project-id = 965012
```

### Applied Mekanistics

```toml
name = "Applied Mekanistics"
filename = "Applied-Mekanistics-1.6.3.jar"
side = "both"

[download]
hash-format = "sha1"
hash = "bec4a47269ec23bca2329742e13409bfde69c5c3"
mode = "metadata:curseforge"

[update]
[update.curseforge]
file-id = 7096962
project-id = 574300
```

### Applied Oritech

```toml
name = "Applied Oritech"
filename = "applied_oritech-1.0.4+1.21.1.jar"
side = "both"

[download]
hash-format = "sha1"
hash = "31b39d0440b77dc6b59a9090586cd8e52944c196"
mode = "metadata:curseforge"

[update]
[update.curseforge]
file-id = 8944224
project-id = 1686167
```

### Applied Pneumatics

```toml
name = "Applied Pneumatics"
filename = "appliedpneumatics-1.21.1-neoforge-1.0.9.jar"
side = "both"

[download]
hash-format = "sha1"
hash = "44e46bc3aac260d594837d4d0fc13ac35d9ca558"
mode = "metadata:curseforge"

[update]
[update.curseforge]
file-id = 8812885
project-id = 1355087
```

### AppliedE

```toml
name = "AppliedE"
filename = "appliede-1.0.8-beta.jar"
side = "both"

[download]
hash-format = "sha1"
hash = "86e302e0d2ee039b74590d13caf50d9636957cfc"
mode = "metadata:curseforge"

[update]
[update.curseforge]
file-id = 6500056
project-id = 1009940
```

### Cable Facades

```toml
name = "Cable Facades"
filename = "cable_facades-1.21.1-NeoForge-2.1.4.jar"
side = "both"

[download]
url = "https://cdn.modrinth.com/data/twipgzWx/versions/XWCAtdzJ/cable_facades-1.21.1-NeoForge-2.1.4.jar"
hash-format = "sha512"
hash = "c7753e7eeeb76b55dee2b66fb6b311d358688dbd20f2b42977cd9366bb9a8be690635ffc5243d470321364d6b1f041673f2a021e43b37794f42c6fcc45671f86"

[update]
[update.modrinth]
mod-id = "twipgzWx"
version = "XWCAtdzJ"
```

### Cablephobic

```toml
name = "Cablephobic"
filename = "cablephobic-1.1.0.jar"
side = "both"

[download]
hash-format = "sha1"
hash = "0134d232bc5b28adfc6e9e5cc4d791aa444dfeb1"
mode = "metadata:curseforge"

[update]
[update.curseforge]
file-id = 9146915
project-id = 1689231
```

### Create: Applied Kinetics

```toml
name = "Create: Applied Kinetics"
filename = "createappliedkinetics-1.5.4-1.21.1.jar"
side = "both"

[download]
hash-format = "sha1"
hash = "dd2df3b8febb78721be502aceff16af979a91314"
mode = "metadata:curseforge"

[update]
[update.curseforge]
file-id = 7789362
project-id = 867328
```

### Create: Sophisticated Backpacks Compat

```toml
name = "Create: Sophisticated Backpacks Compat"
filename = "create_sophback_compat-1.0.jar"
side = "both"

[download]
url = "https://cdn.modrinth.com/data/BAEkFrvn/versions/MGpIgh9e/create_sophback_compat-1.0.jar"
hash-format = "sha512"
hash = "93f18ab387f7c889e9e16d9df6089ec54a1db8e1be8a3404ddeb825dc8f6ccbc736e920a1a5f854951d0f6a7eaf105b936629d6b20d976d6160d3a1e22dae09d"

[update]
[update.modrinth]
mod-id = "BAEkFrvn"
version = "MGpIgh9e"
```

### ExtendedAE

```toml
name = "ExtendedAE"
filename = "ExtendedAE-1.21-2.2.41-neoforge.jar"
side = "both"

[download]
hash-format = "sha1"
hash = "35c40ae1fa8db6b8d9caf37c7394a8bd7fd6f16b"
mode = "metadata:curseforge"

[update]
[update.curseforge]
file-id = 9087065
project-id = 892005
```

### MEGA Cells

```toml
name = "MEGA Cells"
filename = "megacells-4.11.0.jar"
side = "both"

[download]
hash-format = "sha1"
hash = "f0b1a44bf30c8a9e14e2fa7fce37360191aa55e8"
mode = "metadata:curseforge"

[update]
[update.curseforge]
file-id = 7952621
project-id = 622112
```

### Rechiseled: AE2

```toml
name = "Rechiseled: AE2"
filename = "rechiseledae2-neoforge-1.21-1.21.1-1.1.0.jar"
side = "both"

[download]
hash-format = "sha1"
hash = "5142f5b9be2ebfcf8f1938331d879b30ecdf0312"
mode = "metadata:curseforge"

[update]
[update.curseforge]
file-id = 8730525
project-id = 987534
```

### Rechiseled: Applied Energistics 2

```toml
name = "Rechiseled: Applied Energistics 2"
filename = "rechiseledae-1.0.1-neoforge-mc1.21.1.jar"
side = "both"

[download]
hash-format = "sha1"
hash = "e9e8d764ba2cefe7a21001ba51f37b77e5e78047"
mode = "metadata:curseforge"

[update]
[update.curseforge]
file-id = 8302952
project-id = 1483822
```

### Sophisticated Backpack Allthemodium Tier

```toml
name = "Sophisticated Backpack Allthemodium Tier"
filename = "backpack_allthemodium_upgrade-2.4.jar"
side = "both"

[download]
hash-format = "sha1"
hash = "8c383e1d8a7f21cfae83a04bdc693bb72bbd7430"
mode = "metadata:curseforge"

[update]
[update.curseforge]
file-id = 6842146
project-id = 1198838
```

### Sophisticated Backpacks Create Integration

```toml
name = "Sophisticated Backpacks Create Integration"
filename = "sophisticatedbackpackscreateintegration-1.21.1-0.2.3.194.jar"
side = "both"

[download]
hash-format = "sha1"
hash = "6519e78e8aff212826d650b8d803f9a23e1dc1be"
mode = "metadata:curseforge"

[update]
[update.curseforge]
file-id = 9151543
project-id = 1238567
```

### Sophisticated Backpacks

```toml
name = "Sophisticated Backpacks"
filename = "sophisticatedbackpacks-1.21.1-3.26.12.2208.jar"
side = "both"

[download]
url = "https://cdn.modrinth.com/data/TyCTlI4b/versions/QkVLEgt1/sophisticatedbackpacks-1.21.1-3.26.12.2208.jar"
hash-format = "sha512"
hash = "19b503080d0bd0e522e0934fda0b4922f2f0d5ad946103e5daf86be86e01e5e8ec6744f34e915b41fc4d1578fc6cacf004cca5d10ab8bdf76d2f26831e07bb65"

[update]
[update.modrinth]
mod-id = "TyCTlI4b"
version = "QkVLEgt1"
```

### Sophisticated Core

```toml
name = "Sophisticated Core"
filename = "sophisticatedcore-1.21.1-1.5.14.2413.jar"
side = "both"

[download]
hash-format = "sha1"
hash = "87a885f84a8e6d315f4617a2ec06a8886b1f2120"
mode = "metadata:curseforge"

[update]
[update.curseforge]
file-id = 9229216
project-id = 618298
```

### Sophisticated Item Actions

```toml
name = "Sophisticated Item Actions"
filename = "sophisticateditemactions-1.21.1-0.5.17.435.jar"
side = "both"

[download]
url = "https://cdn.modrinth.com/data/RMW7bKVL/versions/qBhmmf9h/sophisticateditemactions-1.21.1-0.5.17.435.jar"
hash-format = "sha512"
hash = "871609e84e514e6c267409cc38bf2757fc19a2e7d8ff365c4c9e32cf71f92947ed237f0a0ab053328338bcafddb38df728cf1ffdb6d2c6447c40bc45d167df11"

[update]
[update.modrinth]
mod-id = "RMW7bKVL"
version = "qBhmmf9h"
```

### Sophisticated Storage Allthemodium tier

```toml
name = "Sophisticated Storage Allthemodium tier"
filename = "storage_allthemodium_upgrade-2.0.1.jar"
side = "both"

[download]
hash-format = "sha1"
hash = "1be6c4ac7741ac4e7cc402b08a50d92c4ba47da8"
mode = "metadata:curseforge"

[update]
[update.curseforge]
file-id = 8241426
project-id = 1200165
```

### Sophisticated Storage Create Integration

```toml
name = "Sophisticated Storage Create Integration"
filename = "sophisticatedstoragecreateintegration-1.21.1-0.1.23.246.jar"
side = "both"

[download]
hash-format = "sha1"
hash = "f5ecb7f95b7a915f047e10ab016edba1c1ee703c"
mode = "metadata:curseforge"

[update]
[update.curseforge]
file-id = 9151604
project-id = 1226755
```

### Sophisticated Storage

```toml
name = "Sophisticated Storage"
filename = "sophisticatedstorage-1.21.1-1.6.7.2184.jar"
side = "both"

[download]
hash-format = "sha1"
hash = "7b72514b21ce7a61c71fe226d1ba46d9ddee767b"
mode = "metadata:curseforge"

[update]
[update.curseforge]
file-id = 9201424
project-id = 619320
```

### Transparent AE2

```toml
name = "Transparent AE2"
filename = "transparentae2-1.21.1-1.1.1.jar"
side = "both"

[download]
hash-format = "sha1"
hash = "419b15348987ad4cdcf1bd7bca4ee6025e346385"
mode = "metadata:curseforge"

[update]
[update.curseforge]
file-id = 9195638
project-id = 1660041
```


## Optional integration dependencies — approved

All targeted third-party mods and extensions are optional to Economy. Economy must start and retain its core banking/trading features with none installed. Activate each adapter only when its actual required parent mods/libraries are present and compatible; optional class references must not load on absent-mod paths. An absent extension disables only its integration, not the entire integration family. Target mods keep their own dependency requirements; optionality to Economy does not waive those requirements.

Use separate integration mods/modules, avoid bundling third-party mod JARs into Economy, and keep their runtime dependencies scoped to the applicable integration development runs. Such runs must register the integration and include all required dependencies; retain working core runClient/runServer without them. Incompatible installed versions must not cause an implicit upstream-version substitution or unsupported adapter activation. Existing saved integration references must preserve their data and return appropriate unavailability rather than lose contents or gain fallback access.

Select proportionate absent-mod/parent-only/extension evidence where loading changes need it, reusing shared-path evidence. This introduces no automatic full combination matrix, test invocation or full-suite gate.
