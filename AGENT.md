# AGENT.md - build and maintain the Lycanites familiars UUID patch

**Project:** patch a Lycanites Mobs 2.0.8.10 jar (Minecraft 1.12.2, Forge) so it reports a fixed
Minecraft UUID to the mod author's familiars web service, and ship a patcher that a non-technical
person can use on Windows or Linux.

**Audience:** an autonomous agent or developer maintaining this repository.
**End users** never need this file; they follow `README.md` / `START-HERE.txt`.

There are two distinct jobs here:

* **A. Rebuild the patched class** from `dev/src/` (needs a JDK).
* **B. Rebuild the distribution** (`classes/`, `tools/`, the `Patch-*` scripts) that end users run
  (needs no Java at all on the user's machine).

---

## 0. Definition of done

For a source rebuild:

1. `cd dev && ./build.sh` (or `.\build.ps1`) ends with `all checks passed` (7/7 scenarios).
2. `classes/com/lycanitesmobs/core/pets/*.class` is refreshed and has exactly three files.
3. `git status` shows only intended changes; **never commit** `put-jar-here/*.jar`,
   `patched/*.jar`, `dev/build/` or `dev/libs/` - `.gitignore` covers them.

For a distribution change:

4. `python3 tools/patch_jar.py --yes` on a real 2.0.8.10 jar produces a jar that passes
   `dev` verification against the original.
5. `Patch-Windows.bat` and `Patch-Linux.sh` still work from a clean copy of the repository.

If you did not run the game, say so; an in-game check cannot be done in CI.

---

## 1. Repository layout

```
README.md              user-facing instructions (non-technical audience)
START-HERE.txt         five-line version of the same
UUID.txt               optional default UUID used by the patcher prompt
Patch-Windows.bat      user entry point (Windows)
Patch-Linux.sh         user entry point (Linux/macOS)
tools/Patch-Jar.ps1    Windows patcher: PowerShell + .NET ZipArchive
tools/patch_jar.py     Linux/macOS patcher: Python 3 stdlib zipfile
classes/               prebuilt patched classes, copied into the user's jar
put-jar-here/          the user drops their unpatched jar here (gitignored)
patched/               patcher output (gitignored, except the placeholder text)
TECHNICAL.md           reverse-engineering write-up of the mod
AGENT.md               this file
dev/                   maintainer build: sources, stubs, tests, BuildTool
  src/com/lycanitesmobs/core/pets/PlayerFamiliars.java   the patched class
  stubs/                 MC signature stubs (compile-time only)
  test/                  standalone class/API/UUID test
  tools/BuildTool.java   JDK-only build + verify + test driver
  build.sh / build.ps1   wrappers
```

Checksums of the files you are expected to reproduce (verify with `sha256sum <file>` or
`Get-FileHash -Algorithm SHA256 <file>`):

| file | sha256 |
|---|---|
| `dev/src/com/lycanitesmobs/core/pets/PlayerFamiliars.java` | `4c55ba49c99f1514b623c75d0d747c6275c8b5d3e317d99266dd426689d0340c` |
| `dev/tools/BuildTool.java` | `908234fe65d0b68656e9c568f8e02292e867813124440f3083121fa47e13e08c` |
| `dev/stubs/net/minecraft/entity/EntityLivingBase.java` | `e1c7c45076b485c5446be8e099afb1f6dd28335d173e9292319f9d53739000fb` |
| `dev/stubs/net/minecraft/entity/player/EntityPlayer.java` | `0a15a7ccffad52ae7a2c4afed424352645e4c5863cf5b4ec0f9bb4f03e38c10d` |
| `dev/test/Test.java` | `3dd4c85dc0a33dc4760223c1bea8396d93253dc653ea0c1894a617f3b0b4a34f` |
| `dev/test/stub/com/lycanitesmobs/LycanitesMobs.java` | `c0e3f6586fc256c0b27e08e70eba5a13761866eb1d96eed63228b3f0917544cb` |
| `tools/patch_jar.py` | `bdc2266ca108c350f8f4c9104fb9dc228faefe9b8e9cc37a7e30d4b2306125aa` |
| `tools/Patch-Jar.ps1` | `e12f56dc6b4070612579d45f9bb509bcafdcd567ba21fa51dc763be5a2030d7a` |
| `classes/com/lycanitesmobs/core/pets/PlayerFamiliars.class` | `7df0bf550e1d3401f0ed8fa1398362dd2753d82002ce89388e3ba4138e9f4984` |
| `classes/com/lycanitesmobs/core/pets/PlayerFamiliars$FamiliarLoader.class` | `c51705698be9c90022a57739fd2beaee62374f8352f4e376c3273645348a83fd` |
| `classes/com/lycanitesmobs/core/pets/PlayerFamiliars$TrustAllCertificates.class` | `c36ce443ef65533eaf7cf5e53c2d8776c4708db7dca8b95ba4c3c8720993c2b3` |

The original mod jar is **not** in the repository. Identity anchor:

```
lycanitesmobs-1.12.2-2.0.8.10.jar
sha256 e92269569cf93407be67632a56a2248dd9a82514c70ae67f05e4db31793f4a76
5356 entries, mcmod.info version "1.12.2-2.0.8.10"
```

`patched/*.jar` hashes change on every rebuild (fresh timestamps), so never treat them as identity.

---

## 2. Requirements

| Role | Needs |
|---|---|
| End user (Windows 10/11) | nothing but Windows: `Patch-Windows.bat` uses PowerShell 5.1 + .NET |
| End user (Linux) | Python 3 (preinstalled on mainstream distros) |
| Maintainer | JDK 8+ (`javac` and `java`, not a JRE), network once for two Maven jars |

Notes:

* `BuildTool` targets Java 8 bytecode (`--release 8`, or `-source/-target 8` on JDK 8) because
  Minecraft 1.12.2 runs on Java 8.
* `dev/libs/` (gson 2.8.0, commons-io 2.5) is downloaded by `BuildTool`; offline, drop the jars in
  by hand.
* `BuildTool` resolves paths relative to the **current working directory**, which must be `dev/`.

---

## 3. Background

Read `TECHNICAL.md`. The three facts that matter for correctness:

1. `ExtendedPlayer.onUpdate()` (server side) -> `loadFamiliars()` ->
   `PlayerFamiliars.getFamiliarsForPlayer(player)` -> background `FamiliarLoader` ->
   `GET https://service.lycanitesmobs.com/api/v1/familiars?minecraft_uuid=<uuid>` with a hardcoded
   bearer token and a trust-all SSL context.
2. `parseFamiliarJSON` stores rows as `playerFamiliars[minecraft_uuid][familiar_uuid]`, and
   `getFamiliarsForPlayer` looks up `playerFamiliars[player.getUniqueID()]`.
3. `PetManager.onUpdate()` deletes a persisted familiar whose UUID is not in
   `playerFamiliars[player.getUniqueID()]`.

Service facts confirmed live 2026-11-16: missing `Authorization` -> 401; unknown UUID -> 200 with
`{"data":[]}`; the certificate **expired 2025-08-21**, so the trust-all context is load-bearing.

---

## 4. The change

Everything in `PlayerFamiliars` must stay as shipped except the following. The same fields, legacy
species table, 1800 s cache, threading, bearer token, user agent and trust-all TLS are required.

### 4.1 Report a fixed UUID

```java
UUID queryUuid = REPORTED_UUID != null ? REPORTED_UUID : ownerUuid;   // was: ownerUuid
URL url = new URL("https://service.lycanitesmobs.com/api/v1/familiars?minecraft_uuid="
        + queryUuid.toString());
```

Resolution order (implemented in `resolveReportedUuid()`, resolved once at class load):

1. `-Dlycanites.familiar.uuid=<uuid>`
2. `lycanitesmobs-familiar-uuid.txt` next to the mod jar (via the class's `CodeSource`)
3. `config/lycanitesmobs-familiar-uuid.txt` in the working directory
4. `DEFAULT_REPORTED_UUID`

`off`/`none`/`false` -> `null` (stock). Invalid -> warn + default. Log the resolved value **and its
source**, because users diagnose from `logs/latest.log`.

### 4.2 Store results under the local player's UUID

```java
UUID reported_uuid = UUID.fromString(familiarJson.get("minecraft_uuid").getAsString());
if (this.familiarBlacklist.contains(reported_uuid.toString())) continue;

UUID minecraft_uuid = ownerUuid != null ? ownerUuid : reported_uuid;   // <-- the remap
if (ownerUuid != null && this.familiarBlacklist.contains(minecraft_uuid.toString())) continue;
```

`parseFamiliarJSON` gains a `(String, UUID)` overload; the `(String)` form is kept and delegates
with `null`. `FamiliarLoader` calls the two-argument form with `player.func_110124_au()`.

Changing only the URL is the classic failure: entries are filed under the reported UUID,
`getFamiliarsForPlayer` finds nothing, and `PetManager` deletes them on next load.

---

## 5. Procedures

### 5.1 Rebuild the class and the jar (maintainer)

POSIX:

```bash
cd dev
./build.sh                                              # defaults below
./build.sh /path/to/original.jar /path/to/output.jar
```

Windows:

```powershell
cd dev
.\build.ps1
```

Direct form, any OS:

```bash
cd dev
mkdir -p build/tool
javac -d build/tool tools/BuildTool.java
java -cp build/tool BuildTool all ../put-jar-here/lycanitesmobs-1.12.2-2.0.8.10.jar ../patched/lycanitesmobs-1.12.2-2.0.8.10.jar
```

`dev/build.sh` also copies `build/classes/com` into `../classes/`, which is what the end-user
patchers use. If you edit the source and forget this step, users get the old class.

### 5.2 The end-user patchers (no Java on the user's machine)

Both patchers implement the same contract:

1. find exactly one `*.jar` in `put-jar-here/`, else explain and stop;
2. validate the jar contains `com/lycanitesmobs/core/pets/PlayerFamiliars.class`;
3. validate `mcmod.info` contains version `2.0.8.10`, else refuse (a mismatched class can crash);
4. copy the jar to `patched/<same name>`;
5. in the copy, replace the three classes from `classes/` (adding `$TrustAllCertificates`, which the
   original lacks) and delete `PlayerFamiliars$1.class`;
6. write `patched/lycanitesmobs-familiar-uuid.txt`;
7. reopen the output and verify the three entries exist, `$1` is gone, and the class still contains
   `func_110124_au`;
8. print the next steps (delete the old jar, copy both files to `mods`).

UUID selection: `--uuid`/`-Uuid` argument, else prompt with the default read from `UUID.txt`, else
the built-in default. Non-interactive runs use the default.

Test after changing either script:

```bash
cp /path/to/lycanitesmobs-1.12.2-2.0.8.10.jar put-jar-here/
python3 tools/patch_jar.py --yes
cd dev && java -cp build/tool BuildTool verify ../lycanitesmobs-1.12.2-2.0.8.10.jar ../patched/lycanitesmobs-1.12.2-2.0.8.10.jar
cd .. && rm -rf put-jar-here/*.jar patched/*.jar patched/*-uuid.txt
```

The PowerShell path cannot be executed on Linux. Keep it a direct translation of
`tools/patch_jar.py`; both use the same entry names and the same verification.

### 5.3 Verify

```bash
cd dev
java -cp build/tool BuildTool verify <original.jar> <patched.jar>
java -cp build/tool BuildTool test   <patched.jar>
```

Expected: seven `ok:`/`PASS` lines and `all checks passed`.

---

## 6. Non-negotiables

1. **SRG names.** Call `EntityPlayer.func_110124_au()`, never `getUniqueID()`. Compile against
   `dev/stubs/`, which only declares the SRG signature.
2. **Re-key the storage** (section 4.2).
3. **Keep the trust-all `SSLContext`**, bearer token, user agent and URL.
4. **Keep the public surface** listed in `TECHNICAL.md` section 2, including
   `parseFamiliarJSON(String)` and `PlayerFamiliars$FamiliarLoader(EntityPlayer)`.
5. **Java 8 bytecode only** (major version 52).
6. **Touch nothing else in the jar.** `verify` enforces unchanged CRCs.
7. **Never re-sign.** The jar has no signatures; adding one breaks loading.
8. **Package every compiled class.** A `private` nested class produces a synthetic accessor that
   references a marker class (`PlayerFamiliars$1`). `TrustAllCertificates` is package-private on
   purpose; `BuildTool` aborts if compilation yields any class outside the replacement list. Do not
   delete that check.
9. **Ship the class files as binary artifacts.** `classes/*.class` is intentional; it is what makes
   the user's machine need no JDK. It must stay in sync with `dev/src/`.
10. **Never commit** user jars or generated output (`put-jar-here/*.jar`, `patched/*.jar`,
    `dev/build/`, `dev/libs/`). `.gitignore` covers them; do not "fix" a dirty tree by adding them.
11. **Keep both patchers in sync.** Same validation, same entry list, same verification, same
    messages. They are separate implementations of one contract (section 5.2).
12. **Do not run `git commit` without being asked**; leave changes staged-but-uncommitted unless the
    owner requests a commit.

Deliberately unchanged from the original mod: expired-cert trust-all, no HTTP timeouts,
`familiar_variant` required, pagination ignored, override is global per installation.

---

## 7. Acceptance checklist

Automated (enforced by `BuildTool`):

- [ ] `verify`: only `PlayerFamiliars*` entries changed; entry count unchanged; not signed; major
      version 52; `func_110124_au` and `parseFamiliarJSON` present.
- [ ] `test`: 7/7 scenarios plus `API OK` for each.

Distribution:

- [ ] `python3 tools/patch_jar.py --yes` on the real jar -> `verify` passes on the output.
- [ ] running it twice reports "already patched" and still produces a working copy.
- [ ] a non-jar file in `put-jar-here/` -> clear error, nothing written.
- [ ] two jars in `put-jar-here/` -> clear error.
- [ ] a different mod version -> clear refusal.
- [ ] `classes/` contains exactly three files.

Manual, in game (hand to the user if you cannot run 1.12.2):

1. Install the patched jar + txt in `mods`, remove the original, start the world.
2. `logs/latest.log` shows `Reporting UUID <uuid> (from ...)` then
   `Online familiars loaded successfully for <uuid>.`
3. Beastiary (or the Pets screen) -> Familiars tab lists the pet; it spawns with the expected
   name/colour/variant.
4. Known reference UUID `a0aed996-9645-44e2-9468-5679fc25f44a` yields one **Wraamon** named
   **Skench**, variant 2.

---

## 8. UUID configuration (user-facing)

| # | Where | Notes |
|---|---|---|
| 1 | `-Dlycanites.familiar.uuid=<uuid>` | advanced, JVM arguments |
| 2 | `lycanitesmobs-familiar-uuid.txt` next to the jar | what the patcher writes; the normal path |
| 3 | `config/lycanitesmobs-familiar-uuid.txt` in the game dir | advanced |
| 4 | built-in default | `a0aed996-9645-44e2-9468-5679fc25f44a` |

Read once per game start -> restart after changes. The mod caches the fetched list in memory for
1800 s. `off` disables the override.

---

## 9. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `No Java compiler available` | JRE, not JDK | use a JDK |
| `compilation produced classes that would not be packaged` | new nested/anonymous class | remove it or add it to `REPLACEMENTS` |
| Build OK, no familiars in game | storage key not remapped | section 4.2 |
| Familiars appear, then vanish | same | section 4.2 |
| Patch "succeeds", game crashes with duplicate mod | both jars in `mods` | remove the original |
| `Unable to access the online familiars service` | TLS verification added, or offline | keep trust-all; check token |
| Wrong account | stale cache or wrong file location | restart; read the `(from ...)` part of the log line |
| Python `zipfile.BadZipFile` | not a jar / truncated download | re-download the mod jar |

---

## 10. From scratch

Decompiler route only; nothing else in this repo requires it.

```bash
curl -sSLo cfr.jar https://repo1.maven.org/maven2/org/benf/cfr/0.152/cfr-0.152.jar
mkdir -p mod && cd mod && unzip -q ../lycanitesmobs-1.12.2-2.0.8.10.jar
java -jar ../cfr.jar --extraclasspath . \
    com/lycanitesmobs/core/pets/PlayerFamiliars.class \
    'com/lycanitesmobs/core/pets/PlayerFamiliars$FamiliarLoader.class' \
    com/lycanitesmobs/core/pets/PetEntry.class \
    com/lycanitesmobs/core/pets/PetManager.class \
    com/lycanitesmobs/core/entity/ExtendedPlayer.class \
    --outputdir ../dec
```

(`--jarfilter` over the whole jar is unreliable; the `--extraclasspath .` form is the one that was
used.) Rewrite `PlayerFamiliars` per section 4, recreate `dev/`, and re-run section 5.1.

---

## 11. Provenance

* Observed: decompiled class bodies, `javap` signatures, jar entry list/CRCs, live HTTP responses
  (2026-11-16), the expired certificate, and the seven UUID-resolution tests executed against the
  compiled class.
* Inferred: the in-game spawn path, from `PetEntry.spawnEntity()` / `PetManager` code. Not executed;
  no Minecraft runtime was available.
* The PowerShell patcher was executed with PowerShell 7.4.6 (Linux build) against the real jar:
  success path, every failure path, and the interactive prompt all behave as documented. Windows
  PowerShell 5.1 itself was not available in this environment.
