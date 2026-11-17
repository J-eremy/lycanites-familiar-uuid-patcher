# Lycanites Mobs 2.0.8.10 - donator familiars and the UUID override patch

Analysis target: `lycanitesmobs-1.12.2-2.0.8.10.jar` (Minecraft 1.12.2, Forge mod, 5356 entries,
sha256 `e92269569cf93407be67632a56a2248dd9a82514c70ae67f05e4db31793f4a76`).
Decompiled with CFR 0.152; class and method names below are exactly as they appear in the jar.

This document explains what the mod does; `AGENT.md` explains how to rebuild the patch.

---

## 1. How the donator ("familiar") feature works

Everything lives in `com.lycanitesmobs.core.pets`:

| Class | Role |
|---|---|
| `PlayerFamiliars` | Singleton (`INSTANCE`), owns the UUID->familiar map and does the HTTP fetch |
| `PlayerFamiliars$FamiliarLoader` | `Runnable` that performs the blocking HTTPS GET on a background thread |
| `PetEntryFamiliar` | Empty subclass of `PetEntry` with type `"familiar"` |
| `PetEntry` / `PetManager` | Generic pet entity: spawning, NBT persistence, client sync |
| `ExtendedPlayer` | Per-player state; calls `loadFamiliars()` on first tick |

### Data flow

```
ExtendedPlayer.onUpdate()                      (server side only: !world.isRemote)
  +- ExtendedPlayer.loadFamiliars()            (first tick, and on demand)
       +- PlayerFamiliars.getFamiliarsForPlayer(player)
            +- if never fetched, or last fetch > 1800 s ago:
            |     new Thread(new FamiliarLoader(player)).start()
            |     +- GET https://service.lycanitesmobs.com/api/v1/familiars
            |              ?minecraft_uuid=<player.getUniqueID()>
            |            Authorization: Bearer 7ed1f44cbc1aff693e604075f23d56402983a4a0
            |            User-Agent: Minecraft 1.12.2 (<os.name>) LycanitesMobs 2.0.8.10
            |        +- PlayerFamiliars.parseFamiliarJSON(json)
            |              playerFamiliars[minecraft_uuid][familiar_uuid] = PetEntryFamiliar
            |     +- ExtendedPlayer.loadFamiliars() -> PetManager.addEntry(entry)
            +- return playerFamiliars.get(player.getUniqueID())
```

Key points:

* **Association with a UUID is purely the `minecraft_uuid` field in the JSON response.** There is
  no Mojang session lookup, no signature, no proof of ownership. The mod does not even compare the
  UUID in the response with the one it asked for; it uses whatever `minecraft_uuid` the response
  contains as the storage key.
* **The fetch runs on the logical server** - the integrated server in singleplayer, the dedicated
  server in multiplayer. Clients only receive the resulting pet entries over the normal
  `MessagePetEntry` packets.
* Results are cached per player UUID for 1800 s (`playerFamiliarLoadedTimes`).
* `familiar_species` is passed through `handleLegacySpecies()` (old donor creature names such as
  `apollyon` -> `archvile`, `zephyr` -> `djinn`, ...) and then used as a normal summon type, so the
  creature spawns through the ordinary pet path (`PetEntry.spawnEntity()`) with the donor's
  `familiar_name`, `familiar_subspecies`, `familiar_variant`, `familiar_size` and `familiar_color`.
* Familiars use type `"familiar"`, and `PetEntry.usesSpirit()` only returns true for `"pet"` and
  `"mount"`, so familiars cost no spirit.
* `PetManager.onUpdate()` discards a persisted familiar NBT entry when its UUID is not present in
  `PlayerFamiliars.INSTANCE.playerFamiliars[playerUUID]`, so the storage key and the lookup key
  must match. This is what makes the re-keying in section 2 mandatory.

### The shipped `assets/lycanitesmobs/familiars.json`

68 KB of legacy donor rows (`donation_username`, `minecraft_uuid`, `familiar_species`, ...) with no
`familiar_uuid` or `familiar_variant` field. No class in the jar references this file; it is a
2016-era dump, not a runtime data source. The live data always comes from the HTTPS API.

### What the service actually returns

`GET /api/v1/familiars?minecraft_uuid=a0aed996-9645-44e2-9468-5679fc25f44a`

```json
{"data":[{"id":586,
          "familiar_uuid":"d2acfe15-4b2e-11ed-a538-028372b28eef",
          "minecraft_uuid":"a0aed996-9645-44e2-9468-5679fc25f44a",
          "familiar_species":"wraamon","familiar_subspecies":0,"familiar_variant":2,
          "familiar_size":0,"familiar_name":"Skench","familiar_color":"000000"}],
 "pagination":{"limit":20,"offset":0,"page":1,"page_total":1,"items_per_page":20,
               "items_this_page":1,"items_total":1,"items_total_page_excess":1},
 "query":""}
```

* Unknown UUID -> the same envelope with `"data":[]`, HTTP 200.
* Missing `Authorization` -> HTTP 401. The bearer token in the jar is the only access control.
* The `minecraft_uuid` query parameter is optional: omitting it returns the global list (page 1,
  limit 20). The mod always sends it and ignores pagination, so a donor with more than 20 familiars
  would only ever see the first 20.
* The TLS certificate for `service.lycanitesmobs.com` expired **2025-08-21**. The mod still works
  only because `PlayerFamiliars` installs a trust-all `X509TrustManager` (`checkServerTrusted` is a
  no-op) and wires it into `HttpsURLConnection`. A verifying client cannot reach the service at all,
  and the response is trivially modifiable by anything that can intercept the request.
* Config option (`LycanitesMobs.preInit`): `Extras -> Familiar Username Blacklist` - a list of UUID
  strings, despite the name.

---

## 2. The patch

`dev/src/com/lycanitesmobs/core/pets/PlayerFamiliars.java` is a drop-in replacement for the shipped
class. Its public surface (`INSTANCE`, `playerFamiliars`, `playerFamiliarLoadedTimes`,
`familiarBlacklist`, `sslContext`, `parseFamiliarJSON`, `getFamiliarsForPlayer`,
`getPlayerFamiliarLoadedTime`, `updatePlayerFamiliarLoadedTime`, `handleLegacySpecies`,
`PlayerFamiliars$FamiliarLoader`) is compatible; `PetManager` and `ExtendedPlayer` need no changes.

Two behavioural differences:

1. `FamiliarLoader.getForPlayerUUID()` sends `REPORTED_UUID` in the query string instead of the
   local player's UUID. The bearer token, user agent, URL and trust-all SSL setup are unchanged.
2. `parseFamiliarJSON(json, ownerUuid)` stores the returned entries under the **local player's**
   UUID. Without this the entries would be filed under the reported UUID and
   `getFamiliarsForPlayer()` / `PetManager`'s NBT filter would never see them - the patch would
   compile, log success, and show nothing in game.

`REPORTED_UUID` is resolved once at class load, in this order:

1. `-Dlycanites.familiar.uuid=<uuid>` JVM argument
2. `lycanitesmobs-familiar-uuid.txt` **next to the mod jar** (first non-empty, non-`#` line)
3. `config/lycanitesmobs-familiar-uuid.txt` in the working/game directory
4. the embedded default, `a0aed996-9645-44e2-9468-5679fc25f44a`

`off`, `none` or `false` restores stock behaviour (own UUID). An invalid value logs a warning and
falls back to the embedded default. The log line names the source, e.g.
`Reporting UUID ... (from file next to the mod jar (lycanitesmobs-familiar-uuid.txt))`.

Source 2 and the patched class are what make the distribution user-friendly: the player copies two
files into `mods` and never edits a config or a launcher argument.

### Entries changed in the jar

```
com/lycanitesmobs/core/pets/PlayerFamiliars.class
com/lycanitesmobs/core/pets/PlayerFamiliars$FamiliarLoader.class
com/lycanitesmobs/core/pets/PlayerFamiliars$TrustAllCertificates.class   (new)
com/lycanitesmobs/core/pets/PlayerFamiliars$1.class                      (removed)
```

Everything else keeps its original CRC32, and the jar is not signed, so no re-signing is needed.

`$1.class` in the original is the anonymous `X509TrustManager`. The replacement uses a named
package-private class instead: a `private` nested class makes javac emit a synthetic accessor
constructor that references a marker class (`PlayerFamiliars$1`), which would have to be packaged
too. The build fails if compilation produces any class that is not in the list above.

### Build (maintainers)

Requires a JDK 8+. From this repository:

```bash
cd dev
./build.sh                       # uses ../put-jar-here/lycanitesmobs-1.12.2-2.0.8.10.jar
./build.sh /path/to/original.jar /path/to/output.jar
```

```powershell
cd dev
.\build.ps1
```

`dev/build.sh` compiles signature stubs for `net.minecraft.entity.player.EntityPlayer` /
`EntityLivingBase` (so no Minecraft/Forge install is needed), compiles the replacement class as
Java 8 bytecode, rewrites the jar with `java.util.zip`, verifies it, runs the test suite, and
refreshes the prebuilt classes in `../classes/` that the end-user patchers copy into the jar.

### Install (players)

Replace the original jar in `mods/` **on the side that loads the world** - the client instance in
singleplayer, the server in multiplayer - and put `lycanitesmobs-familiar-uuid.txt` next to it.
Remove the unpatched jar: two copies of the same mod crash the game on startup.

---

## 3. Verification

`BuildTool verify` checks, on any OS:

* every jar entry except the four listed above has an unchanged CRC32,
* entry count is unchanged (+1 new class, -1 stale `$1.class`),
* the jar is not signed,
* `PlayerFamiliars.class` is Java 8 bytecode (major version 52),
* the class references `EntityPlayer.func_110124_au()Ljava/util/UUID;` - the same SRG name the
  rest of the 1.12.2 mod uses (`PetManager` calls it too),
* `parseFamiliarJSON(String)` still exists.

`BuildTool test` loads the patched class outside Minecraft (stubbed `net.minecraft` + stubbed
`com.lycanitesmobs.LycanitesMobs`), asserts the public API is intact, and runs seven scenarios:

| scenario | expected |
|---|---|
| default (no config) | embedded default |
| config file (game dir) | value from the file |
| JVM argument + file | JVM argument wins |
| file says `off` | `null` (stock behaviour) |
| invalid value | warning + embedded default |
| no config dir | embedded default |
| file next to the jar | value from that file |

All seven pass against the shipped jar.

Upstream behaviour for the target UUID was confirmed with a direct HTTPS request (see section 1).

Not verified: an actual game launch. Minecraft 1.12.2/Forge cannot run in the build environment, so
the in-game check is the installer's. The manual checklist is in `AGENT.md` section 7.

---

## 4. Notes

* The original `HttpsURLConnection` sets no connect/read timeout; a dead service leaves the loader
  thread blocked (harmless, but no familiars until the next session). Left as-is to keep the patch
  minimal.
* `parseFamiliarJSON` requires `familiar_variant`; a row without it is silently skipped (same as
  stock).
* Because the response is unauthenticated and the TLS context trusts everything, a local proxy that
  rewrites the `data` array would achieve the same result. This patch avoids that extra moving part.
* If you want *all* donor familiars at once, the service also answers without a `minecraft_uuid`
  parameter (page 1, 20 rows); that would need pagination handling the mod does not have.
