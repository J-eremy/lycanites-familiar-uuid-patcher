# Lycanites Mobs - Familiar UUID Patcher

This turns your Lycanites Mobs mod jar into a patched copy that shows **the donator familiars
belonging to a chosen Minecraft account**.

Nothing else about the mod changes. Your world, config, items and creatures are untouched.

You do **not** need to install Java, Python, Forge, or any developer tools to use this on Windows.
On Linux you need Python 3, which almost every distribution already has.

## Why this exists

Someone's Microsoft account was compromised, and Microsoft support could not or would not help
recover it, so they had to start over on a new account. Their donator familiar is registered on the
Lycanites service against the UUID of the old account, so on the new account it simply never
appears.

This patcher makes the mod ask the service for the familiar list of the old UUID while the player
uses the new account, so the familiar follows them again.

It is a stopgap, not a permanent fix. It gives nothing the old account did not already have, and
once the Lycanites team can move the familiar to the new UUID it should be removed: restore the
original jar and delete the `.txt` (see "Removing the patch" below).

---

## The fast version

1. Copy your Lycanites Mobs jar into the folder **`put-jar-here`**.
2. Run the patcher:
   * **Windows:** double-click **`Patch-Windows.bat`**
   * **Linux:** open a terminal in this folder and run **`bash Patch-Linux.sh`**
3. Press **Enter** when it asks which UUID to use (or type one).
4. Copy the **two** files from the **`patched`** folder into your Minecraft **`mods`** folder,
   after removing the old Lycanites Mobs jar from there.

That is all. Start the game and check `logs/latest.log` for the line shown in step 7 below.

---

## Before you start

You need:

* Windows 10 or 11, or a Linux desktop.
* Minecraft **1.12.2** with **Forge** already installed and working.
* The **`lycanitesmobs-1.12.2-2.0.8.10.jar`** file (the Lycanites Mobs version this patcher is
  made for). The patcher refuses any other version, because replacing the code in a different
  version could crash the game.
* An internet connection when you play (the mod downloads the familiar list from its own website).

Where is my `mods` folder?

* Official Minecraft launcher: `%appdata%\.minecraft\mods`
  (paste that into the address bar of File Explorer).
* CurseForge, Prism, MultiMC, ATLauncher, etc: it is the `mods` folder **inside the instance** you
  play. It is the same folder your Lycanites Mobs jar is in today.

---

## Step by step

### 1. Put your jar in `put-jar-here`

Drag `lycanitesmobs-1.12.2-2.0.8.10.jar` from your `mods` folder into the **`put-jar-here`**
folder that is next to this README.

Leave the original where it is for now, so you still have a working game.

### 2. Run the patcher

**Windows**

Double-click **`Patch-Windows.bat`**.

If Windows shows a blue box saying *"Windows protected your PC"*, click **More info**, then
**Run anyway**. That warning appears because the file was downloaded from the internet; the script
is plain text and you can open it in Notepad to read it.

**Linux**

Open a terminal in this folder (right-click inside the folder, *Open in Terminal*) and run:

```bash
bash Patch-Linux.sh
```

If you get *"Python 3 is needed but was not found"*, install it with the command the patcher prints,
then run it again.

### 3. Answer the question

The patcher asks:

```
Which Minecraft UUID should the mod report?
(Press Enter to use: a0aed996-9645-44e2-9468-5679fc25f44a)
```

* Press **Enter** to use the UUID shown. It is already filled in for you.
* Or type a different UUID and press Enter. A UUID looks like
  `12345678-1234-1234-1234-123456789abc` (letters a-f and digits, in 5 groups).

**What is a UUID?** Every Minecraft account has a long ID number like that. Donator familiars are
stored on the mod author's website under that ID. The patched mod asks the website for the
familiars of *that* ID instead of your own.

If you want to change it without typing it every time, put your UUID in the file **`UUID.txt`** in
this folder before running the patcher.

### 4. Check the result

The patcher prints where it wrote the files, and finishes with a summary like:

```
Done. Patched jar written to:
   .../patched/lycanitesmobs-1.12.2-2.0.8.10.jar

It will report this UUID:
   a0aed996-9645-44e2-9468-5679fc25f44a
```

If you see `ERROR:` instead, nothing was changed. Read the message, fix what it says, and run it
again. The **Something went wrong** section below covers the usual cases.

### 5. Copy two files into `mods`

In the **`patched`** folder you now have:

| file | what it is |
|---|---|
| `lycanitesmobs-1.12.2-2.0.8.10.jar` | the patched mod |
| `lycanitesmobs-familiar-uuid.txt` | tells the patched mod which UUID to use |

Do this:

1. Open your `mods` folder.
2. **Delete the old `lycanitesmobs-1.12.2-2.0.8.10.jar`** (or move it somewhere safe as a backup).
   Keeping both would mean two copies of the same mod, which crashes the game on startup.
3. Copy **both** files from `patched` into `mods`.

The two files must stay next to each other in `mods`. That is how the patched jar finds the UUID.

### 6. Start the game

Start Minecraft 1.12.2 with Forge as usual and log in to your world.

### 7. Check that it worked

Open `logs/latest.log` in your game folder (with Notepad, or any text editor) and search for
`Familiars`. You should see lines like:

```
[Familiars] Reporting UUID a0aed996-9645-44e2-9468-5679fc25f44a (from file next to the mod jar (...)) to the online familiars service.
Online familiars loaded successfully for a0aed996-9645-44e2-9468-5679fc25f44a.
```

In game, open the **Beastiary** and switch to its **Familiars** / **Pets** tab. The default key is
usually `B`; the exact keys are under *Options -> Controls -> Lycanites Mobs*. The familiars should
be listed. Summon one and it should appear with its donor name, colour and variant.

The text after `from` tells you where the UUID came from, which makes troubleshooting easy.

---

## Something went wrong

| What you see | What it means | What to do |
|---|---|---|
| `No jar found in ...put-jar-here` | the folder is empty | put the Lycanites Mobs jar in `put-jar-here` |
| `More than one jar is in ...` | two jars | keep only the Lycanites Mobs jar in there |
| `This does not look like a Lycanites Mobs jar` | wrong file | you picked the wrong jar |
| `This patcher is built for Lycanites Mobs 2.0.8.10, but your jar says: ...` | different version | get the 2.0.8.10 jar for 1.12.2 |
| `Python 3 is needed but was not found` (Linux) | Python missing | run the install command the patcher prints |
| Game crashes on startup, mentions "Duplicate mod" | both jars are in `mods` | remove the old one, keep only the patched jar |
| Game starts, but no familiars | wrong UUID, or no internet | check the `[Familiars]` lines in `logs/latest.log` |
| `Unable to access the online familiars service` | the mod's website is unreachable | check your internet / firewall, try again later |
| Windows says "Windows protected your PC" | download warning | More info -> Run anyway |
| Antivirus complains about the `.bat` | it runs PowerShell | open it in Notepad to see it only calls `tools\Patch-Jar.ps1` |
| `ERROR: Patching failed: ...` | a file in the `classes` folder is damaged or in use | close anything using it, or re-download the patcher |

If a line says `Reporting UUID ... (from built-in default)`, the `lycanitesmobs-familiar-uuid.txt`
file is not next to the jar. Copy it into `mods` again.

---

## Changing the UUID later

Two ways, both need a game restart:

* **Easiest:** edit `lycanitesmobs-familiar-uuid.txt` in your `mods` folder with Notepad, change the
  UUID, save, restart the game. You do not need to run the patcher again.
* Or run the patcher again with a different UUID and copy the new `.txt` over.

The mod checks this list, first match wins:

1. a Java argument `-Dlycanites.familiar.uuid=<uuid>` (advanced; ignore unless you know why)
2. `lycanitesmobs-familiar-uuid.txt` next to the mod jar
3. `config/lycanitesmobs-familiar-uuid.txt` inside your game folder
4. the UUID built into the jar

Putting `off` in the file turns the feature off and uses your own account's UUID, as in the
unpatched mod.

---

## Removing the patch

1. Delete `lycanitesmobs-1.12.2-2.0.8.10.jar` and `lycanitesmobs-familiar-uuid.txt` from `mods`.
2. Put your original, unpatched jar back.
3. Start the game.

Your world is not affected either way.

---

## Questions

**Does this give me creatures or items I did not have?**
No. Donator familiars are a cosmetic pet that follows you. This only changes which account's pet
the mod asks for.

**Will other players see it?**
The familiar is spawned for you. Other players will see the pet next to you, the same as any pet.

**Does it work on a server?**
The familiar list is downloaded by the server side of the game, so the patched jar (and the `.txt`)
must be on the **server** for multiplayer. In singleplayer your client is the server, so the normal
instructions are enough.

**Is my account or password involved?**
No. No login, no password, no account token. The patcher only copies a text UUID into a file.

**Does the patcher send anything anywhere?**
No. It only rewrites the jar on your disk. Nothing is uploaded.

**Why only version 2.0.8.10?**
The patch replaces one compiled class. It has to match the rest of that exact mod version, so other
versions are refused instead of risking a crash.

---

## What is in this folder

You only ever need to run one file: `Patch-Windows.bat` or `Patch-Linux.sh`.

| path | purpose |
|---|---|
| `Patch-Windows.bat` | the patcher (Windows) |
| `Patch-Linux.sh` | the patcher (Linux) |
| `put-jar-here/` | **you put your jar here** |
| `patched/` | the patcher writes the result here |
| `UUID.txt` | optional: your preferred UUID |
| `tools/` | the actual patch scripts |
| `classes/` | the patched code that gets put into the jar |
| `dev/` | source code and build scripts (for developers only) |
| `TECHNICAL.md` | what the mod does and what the patch changes |
| `PROMPT.md` | a self-contained prompt that makes an AI agent build the patch itself, from source, without downloading anything from this repo |
| `AGENT.md` | instructions for an AI/developer rebuilding this from scratch |

---

## For the technical person

* The patcher is `tools/Patch-Jar.ps1` (Windows, .NET `ZipArchive`) and `tools/patch_jar.py`
  (Linux, Python `zipfile`). They replace exactly three class entries, drop one stale entry, and
  leave every other byte of the jar untouched.
* The patched class is prebuilt in `classes/` (Java 8 bytecode). Rebuild it from `dev/` with a JDK:
  `cd dev && ./build.sh`.
* `AGENT.md` explains the mod internals, the exact code change, and the verification suite
  (`cd dev && java -cp build/tool BuildTool all ../put-jar-here/<jar> ../patched/<jar>`).
* `TECHNICAL.md` is the reverse-engineering write-up: the familiar endpoint, the UUID association,
  and why the response has to be re-keyed to the local player.
