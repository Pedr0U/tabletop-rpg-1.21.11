# Implementation Report

## Status

**PARCIAL** — everything implemented and compiling is verified; two items need a
runtime test by the user (persistence round-trip, Status layout) and one item
is blocked on the user for information (downed camera).

Last build: `BUILD SUCCESSFUL`. Encoding scan of all touched files: 0 non-Latin
characters. Nothing was committed this round.

## Objective

Split free **Skills** from fixed **Perícias** across model, protocol and UI;
ship the free camera (including the Free game mode), fix the time slider label,
persist the character sheet in the player's NBT, then close the documentation.

## Scope / Subtasks

1. SheetData split: Skills (name + description) vs Perícias (value + attribute).
2. Separate network payloads, receivers and `/rpg roll` on Perícias.
3. Rewrite `SkillsScreen` (free list, removal, description popup).
4. `StatusScreen`: 20 Perícias in one column, wider panel.
5. Time slider: `+6` on the label; verify the real-time drag path.
6. Free camera: available in the Free mode, body stopped, any player.
7. Persist the sheet in the player NBT.
8. Downed camera investigation.
9. Documentation: `.docx` copy, this report, memory.

## What Changed

### SheetData (`src/main/java/com/pedro/tabletoprpg/SheetData.java`)

- `Skill(String name, String description)` — free list, no value, no attribute.
- `Pericia(String name, int value, Attribute attribute)` — value clamped `0..3`,
  defensive attribute fallback.
- `SkillOp { ADD, REMOVE, INVALID }`; `PericiaOp { SET_VALUE, SET_ATTRIBUTE, INVALID }`.
- `PERICIAS_PADRAO` written by hand (4 real Perícias + 16 placeholders);
  `sanitizePericias(...)` forces exactly that list, so a payload or a save file
  cannot add, remove or rename a Perícia.
- `SKILL_DESC_MAX = 10_000` is a **protocol safety limit**, not a user-facing
  limit — `ByteBufCodecs.stringUtf8(n)` throws when decoding an oversized
  string, which would drop the connection.
- `sanitizeSkills` `break`s at `MAX_SKILLS` (line 951), which is what makes the
  unbounded NBT list below safe.

### Persistence (new)

- `SheetData.CODEC` — DataFixer `Codec` with a sub-codec per record group and
  `ATTRIBUTE_CODEC` stored by **field name** (`"strength"`) rather than by
  abbreviation, because the abbreviation is UI text that may change.
- Every field is `optionalFieldOf(..., default)`, and the compact constructor
  runs after decoding — so a missing or corrupt `pericias` still goes through
  `sanitizePericias` and returns to the fixed list. An old or hand-edited save
  cannot break login.
- `PlayerSheetPersistenceMixin` stores/reads the sheet in
  `addAdditionalSaveData` / `readAdditionalSaveData` of `Player`.

### Networking / commands

- `SheetSkillPayload` reduced; `SheetPericiaPayload` added; both registered.
- `/rpg roll <perícia>` now rolls `d20 + value + attribute`; the no-argument
  list and the autocomplete read `sheet.pericias()`.

### UI

- `SkillsScreen`: free list, `X` removal, hover truncated to 100 characters,
  full description in a scrollable popup below the list.
- `StatusScreen`: 20 Perícias in a single right-hand column, panel widened via
  `CharacterSheetScreen.maxPanelWidth()` (520), smaller fonts and buttons.

### Camera

- `PlayerLockPayload` now carries the `GameMode` ordinal; invalid indexes fall
  back to Free.
- `SpectatorCameraController`: `isFreeModeAvailable()`, `requestMode()`,
  `enforceModeForSession()`, and `cycleMode()` skipping Free when not allowed.
- **Activation fixed:** the guard was `if (!locked) deactivate()`, but nobody is
  locked in the Free mode, so the free camera could never turn on exactly where
  it was released. Now: `locked || (mode == FREE && free mode available)`.
- `isFreeCameraActive()` (= `active && mode == FREE`) drives "body stopped":
  - `LocalPlayerMixin` cancels `aiStep` — otherwise WASD moves camera *and*
    player at the same time.
  - `MinecraftMixin` swallows attack/use — cancelling `aiStep` does not stop
    clicks in `handleKeybinds`.

### Time slider

- `+6` added to the displayed hour.
- Verified by bytecode that `onDrag` → `setValueFromMouse` → `setValue` →
  `applyValue()`: the real-time send during the drag **already worked**. No
  functional code was added; a javadoc claiming otherwise was corrected.

## Files Changed

Modified:

- `src/main/java/com/pedro/tabletoprpg/SheetData.java`
- `src/main/java/com/pedro/tabletoprpg/RpgNetworking.java`
- `src/main/java/com/pedro/tabletoprpg/MasterCommands.java`
- `src/main/resources/tabletop-rpg.mixins.json`
- `src/client/java/com/pedro/tabletoprpg/client/SkillsScreen.java`
- `src/client/java/com/pedro/tabletoprpg/client/StatusScreen.java`
- `src/client/java/com/pedro/tabletoprpg/client/CharacterSheetScreen.java`
- `src/client/java/com/pedro/tabletoprpg/client/SpectatorCameraController.java`
- `src/client/java/com/pedro/tabletoprpg/client/TabletopRpgClient.java`
- `src/client/java/com/pedro/tabletoprpg/client/TimeSlider.java`
- `src/client/java/com/pedro/tabletoprpg/client/mixin/LocalPlayerMixin.java`
- `src/client/java/com/pedro/tabletoprpg/client/mixin/MinecraftMixin.java`

Added:

- `src/main/java/com/pedro/tabletoprpg/mixin/PlayerSheetPersistenceMixin.java`
- `agent/reports/2026-09-25_arquitetura-docx-fase3-completa.docx` (untracked,
  deliberately **not** committed)

## Decisions

- **Skills and Perícias are fully separate concepts**, not one list with
  optional fields — this was the user's explicit choice.
- Perícias have no description field: the user asked for value and attribute
  only.
- Attribute is persisted by field name, not by the 3-letter abbreviation.
- `PlayerSheetPersistenceMixin` sits in the **common** mixin list, not
  `server` — see the error below.
- The mixin is guarded by `instanceof ServerPlayer` so a pure client never
  writes into its UI cache.
- The `.docx` is copied into `reports/` but never committed.

## Validation

| Check | Result |
|---|---|
| `.\gradlew.bat build --console=plain` | `BUILD SUCCESSFUL` |
| `test` task | `NO-SOURCE` (no tests in project) |
| Non-Latin scan, all touched files | 0 hits |
| Mixin compiled and packaged in the jar | confirmed |
| `sanitizeSkills` caps at `MAX_SKILLS` | confirmed, line 951 |
| `characterSheets` never cleared, `removeSheet` never called | confirmed |

Runtime (in game): **not** verified this round. `ValueOutput.store` and
`ValueInput.read` only execute at runtime, so the persistence round-trip is
proven by code reading, not by observation.

## Problems Encountered

### 1. Mixin `server` list does not run on the integrated server — my error, caught and fixed

I first registered `PlayerSheetPersistenceMixin` in the `"server"` section of
`tabletop-rpg.mixins.json`. The javadoc of `PlayerPoseMixin` (lines 32-37)
documents that the Mixin `server` list only applies to a **dedicated** server
and *not* to the integrated server of singleplayer/LAN. The sheet would
therefore not have persisted in singleplayer — the exact place the user tests.

Fixed by moving it to the common `"mixins"` list with an explicit
`instanceof ServerPlayer` guard, which is the pattern `PlayerPoseMixin`
already uses and documents.

### 2. The stored hypothesis for the downed camera was wrong

The project memory carried the hypothesis "forced `SWIMMING` pose + vanilla
body yaw" as the cause of the downed camera. Bytecode inspection of 1.21.11
disproves it:

- `Entity.isVisuallySwimming()` returns exactly `hasPose(Pose.SWIMMING)`.
- `LivingEntity.isVisuallySwimming()` = `Entity.isVisuallySwimming() ||
  isFallFlying() || hasPose(FALL_FLYING)`.
- `swimAmount` ramps up by `0.09` per tick up to `1.0` while
  `isVisuallySwimming()` is true.

So forcing `Pose.SWIMMING` is **sufficient and correct** — it is what makes the
renderer take the swimming path. The forced pose is not the bug.

**No downed-camera file was modified this round**, because there was no
evidence justifying a change, and shipping a render hack on top of a disproven
hypothesis would be a guess.

## Root Causes

- **Free camera never activating in Free mode** — FACT: the activation guard
  tested only `locked`, and no player is locked in the Free mode. The feature
  was released into a branch of the code that could not reach it.
- **Body not stopped in free camera** — FACT: `aiStep` was only cancelled for
  `locked`/`downed`. In the Free mode the player is not locked, so WASD drove
  the camera and the player simultaneously.
- **Mixed-language corruption in source** — the assistant's own writes inserted
  CJK characters into Java comments and strings (five occurrences, found by the
  encoding scan, not by reading). All were fixed. The literal examples are
  deliberately not reproduced here: writing them down put them back into the
  repository. A scan is now part of the build+verify loop, and it covers
  `agent/memory/*.md` and `agent/reports/*.md`, not only the Java sources.

## Fixes

- Activation guard, `isFreeCameraActive()`, and the two mixin conditions.
- Mixin relocated to the common list with a `ServerPlayer` guard.
- `SheetData.CODEC` + `PlayerSheetPersistenceMixin` for NBT persistence.
- All non-Latin characters removed.

## Remaining Issues

1. **Downed camera — blocked on the user.** The prior hypothesis is dead and I
   have no confirmed new cause. Needs the exact symptom (or a screenshot).
   Note for whoever picks it up: in 1.21.11 the humanoid model is driven by a
   precomputed `HumanoidRenderState` (`HumanoidRenderState.isFallFlying`, etc.)
   and the player renderer was renamed to `AvatarRenderer`; `setupRotations` no
   longer exists on it. So the old "override setupRotations" idea is not
   directly portable.
2. **Persistence runtime test** — the user should: join, edit the sheet, leave,
   rejoin. The log line `[TabletopRPG] Ficha carregada do NBT de ...` says
   whether `read` ran.
3. **Status layout on narrow screens** — `StatusScreen` uses
   `perW = max(140, ...)` and `leftW = max(120, ...)`, so below roughly a 270px
   panel the two columns overlap. Not validated in game.
4. **The `.docx` does not include this round's free-camera and persistence
   work** — it reflects its own last save (25/09/2026 21:43).

## Lessons / Memory

- **Check the Mixin `server` list semantics before trusting it.** On Fabric it
  means *dedicated* server only; singleplayer/LAN run the integrated server and
  do not get those mixins. Guard with `instanceof ServerPlayer` instead.
- **When a free-standing hypothesis is recorded in memory, treat it as
  unverified until bytecode or a test confirms it.** The downed-camera
  hypothesis survived in project memory across rounds and was still wrong when
  finally checked.
- In 1.21.11, `Player` save/load uses `ValueInput`/`ValueOutput`, which only
  expose `Codec`-based `read`/`store` — not a raw `CompoundTag`.
- `Codec` fields with `optionalFieldOf` defaults are the right way to make
  stored data forward/backward tolerant.
- The non-Latin scan must run after **every** write, not once at the end of a
  phase.

## Next Steps

1. Runtime-test the persistence round-trip.
2. Collect the exact downed-camera symptom, then re-investigate.
3. Check the Status layout at a small GUI scale.
4. Update the `.docx` to include the free camera and the NBT persistence.
5. Commit (excluding the `.docx`) once the runtime tests pass.
