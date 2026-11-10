# 立体四子棋 Qubic Tower: project guidance

Qubic (4 x 4 x 4 gravity four-in-a-row, 76 lines) for PICO OS 6 Shared Space, PICO Spatial SDK BOM 6.1.9, package
`io.embodit.qubictower`, v0.1.1 (v0.1.0 + the headset feedback 2026-11-09: precise selection, see below). Design contract: spatial-app-factory `apps/qubic-tower/design-package.json` (v2; profile A12:
one main loop, `v2[]` not built: no drag-to-drop, no tilt setting, no hover follow, no replay / import, no puzzles, no skins,
no 3x3x3 / 5x5x5, no streaks, no danger pegs, no one-tap drop, no coin). Skeleton from the Royal Ur project (itself Five
Stones / CloudAir: two containers, theme, sound pool, level gate, board-input template); Qubic rules / AI / flow / scene are new.

## Surfaces
- **Tower volume** (default container `QubicBoard`, Volumetric 0.60 x 0.42 x 0.46 m, fixed scale, gravity aligned):
  graphite plinth 0.54 x 0.04 x 0.30 + wedge; the tower base tipped 20° (pivot = wedge front-bottom edge); a turntable
  Ø 0.27 with rim labels A–D / 1–4 on four sides; 16 frosted pegs (BLEND 0.35); 64 beads (amber lacquer / celadon glaze,
  OPAQUE) — 32 on each tray's 8 short pegs; x-ray beams (unlit, depth test off) for the lines through the aimed cell;
  three tags on the plinth front (seat tag + two tray counters). No buttons in the volume.
- **Control window** (`QubicControl`, Planar 560 x 600 dp, `Placement.right(QubicBoard)`), native SpatialUI `TabBar`:
  对局 / 新局 / 规则 / 设置. [重开控制窗] closes and reopens beside the tower (D44: no "in front of you"). [退出应用]
  closes both containers, removes the tasks, exits the process.

## Code
| Path | What |
|---|---|
| `domain/Qubic.kt` | pegs / cells / 76-line table (generated), bitboards, gravity, threats, outcome |
| `domain/Engine.kt` | EASY (one ply, misses cross / space blocks 40 %), SERIOUS (α-β depth 4, 20 % top-2), HARD (threat-space search + α-β + TT, 1.5 s) |
| `domain/GameFlow.kt` | the single `PlayState`, phases S02–S09, two-step drop, AI telegraph, undo / redo groups, turntable, confirm, save |
| `domain/Display.kt` | which beams are on (lines through the focus cell, must-block, win) — shared by scene, panel and tests |
| `domain/Turntable.kt` | push maths: hand travel -> angle, snap with ±5° hysteresis |
| `domain/Layout.kt` | metres for everything (design coordinates), tilt / turn transforms, eyes, obstacles, see-through blockers |
| `ui/board/*` | `BoardScene` (all entities, per-frame in `onFrame` via `BoardFrameSystem`), `BoardScreen` (board-input routing, rim push, tags) |
| `ui/control/*` | the four pages; every Text logs `OVERFLOW <tag>` when cut |
| `platform/*` | Application / Graph, SoundPlayer, WindowCtl (+ LevelGate); debug builds add `DebugCommandReceiver` |
| `tools/` | `build_models.py` (all GLBs, `--test-f` = F↑ rim labels), `import_cc0_sounds.py` + `make_sounds.py`, `build_icon.py`, `loudness.py`, `shots.py`, `blank.py`, `qt.sh` |

## Precise selection (v0.1.1, board-input README「精确选择」, board-game §15.5)
S04 is the confirm phase: the first pinch on a peg only selects it (blue light column round the peg + blue ring on its
top, drawn by the app, plus the hanging bead / ghost ring / lines); a second pinch on the same peg, [确定], the trigger or A
drops; another peg re-selects; the plinth, [取消] or B cancels. Up / down / left / right (`domain/Nudge.kt`) move the
selection one peg as the user sees the tower (up = the far row), following the turntable's quarter turn; full pegs are
stepped over, the edge stops. Sources, all into `GameFlow.nudge / confirmAim / cancelSelection`:
- the control window's game page swaps its buttons for a 64 dp cross + [确定] [取消] [左转] [右转] while S04 is on;
- `platform/PadInput.kt`: SDK `ControllerTrackingProvider` actions (stick past 0.6 then back to centre = one step;
  A / X confirm, B / Y cancel, trigger confirms only if no board TAP came within 300 ms), Android key events (DPAD_*,
  BUTTON_A / B / R2, ENTER, ESCAPE, BACK — consumed only in S04) and joystick axes, each logged as `PAD …`
  (logcat + `files/input-log.txt`). Whether Shared Space delivers any of them is the open question in
  feedback/pico-cli-feedback.md 待核实「手柄摇杆方向事件」.
Selecting, moving and cancelling are silent; ticks and the land share one stream (SoundPlayer.GROUP).
Pegs: holder `peg:<n>` (collider + Interactable + system HoverEffect) with children `pegModel:<n>` (frosted GLB) and
`pegMark:<n>` (Ø 5 mm opaque cap on the top). Names only from `ui/board/BoardNames.kt`; replay tests in
`app/src/test/.../ui/board/BoardTargetTest.kt` (incl. the v0.1.0 headset input-log).

Rules - state - render: the scene only renders `PlayState`. Volume input goes through the factory board-input template
(`ui/board/input/BoardInput.kt`, D50); colliders: `peg:<n>` (5 x 24.6 x 5 cm holder over the peg model + marker, + system hover),
`plinth` (thin top box: cancel the aim), `grip` (12 boxes round the turntable rim: drag = push the turntable).
Every pinch goes through `route()` to the same `GameFlow` calls the panel and the debug broadcast use.

## Build / test (kj-mbp-m5)
```bash
export JAVA_HOME=~/.pico/primer-cli/jdk/jdk-21.0.13+9/Contents/Home
./gradlew assembleDebug :app:testDebugUnitTest      # rules, AI ladder (200 games x 3), flow, layout / D35, turntable jitter, display
python3 tools/build_models.py ; python3 tools/import_cc0_sounds.py ; uv run --system-certs --with numpy --with scipy python3 tools/import_beads.py ; python3 tools/make_sounds.py ; python3 tools/build_icon.py --icon512 icon.png
DEV=emulator-5562 tools/qt.sh install
```
Emulators are shared: `python3 -I bin/emu_borrow.py borrow qubic-tower` in the factory, `return` after.

## Debug broadcast (QA screenshots)
`adb shell am broadcast -a io.embodit.qubictower.DEBUG -p io.embodit.qubictower --es cmd <cmd> --es a "<arg>"`
(or `tools/qt.sh cmd <cmd> [arg]`). Log tag `QubicTower`.

| State | Command | Notes |
|---|---|---|
| S00 准备中 | `s00hold on`, force-stop + launch, capture within 10 s, `s00hold off` | three graphite stand-in boxes |
| S01 第一次 | `goto S01` | easy game (you = amber, first) + rules page; [开始] / the first pinch ends it |
| S02 新局 | `goto S02` | [继续上一盘] on top when a save exists |
| S03 待投 | `goto S03` (4 beads in) · `lit on` = first-game lit tops · `hint on` = green ring + dot | |
| S04 瞄准 | `goto S04` (A1 corner, 7 lines) · `aim B2` · `aim B1` (4 lines) · `import 5555666` + `aim B2` (layer 4) | |
| S05 落子中 | `goto S05` (slow 4, drop after 1.2 s) | |
| S06 AI 思考中 | `goto S06` (hard, held) · `hold off` | |
| S07 收珠中 | `goto S07` (slow 4) | |
| S08 警示 | `goto S08` (must-block) · `import 555516` + `aim B2` (full peg) | |
| S09 终局 | `win column|row|flat|cross|space` · `draw` + `drop 15` | |
| S10 面板已关 | `closepanel` · `tap peg:5` (= the first pinch, reopens) | |
| S11 设置 / S12 规则 | `goto S11` / `goto S12` · `exitask` | |
| S13 确认 | `goto S13` (restart) · `resign` · `exitask` · `newask` · 2P undo: `new pvp`, `import 5a`, `undo` · `confirm` / `no` | |
| S14 转塔中 | `goto S14` (slow 4) · `turn 0-3|left|right` · `push <deg>` | |
| S15 恢复 | force-stop, relaunch | moves + orientation restored |
| S04 confirm phase (v0.1.1) | `goto S04` then `nudge up|down|left|right` · `pad confirm|cancel|up…` · `stick <x> <y>` (send `stick 0 0` between pushes) · `padstatus` · `adb shell input keyevent DPAD_RIGHT` (Android key path) | |
| Hit replay | `replay <peg> [hand-ray|eye-hand|controller]` | real scene entities -> resolveUp -> the template's onTap; `REPLAY … -> square=` |
Modifiers: `lang zh|en|system`, `reduced on|off`, `sound on|off`, `volume 0-2`, `slow 1|2|4`, `freeze MS|off`, `eye E0|E1|E2|off`
(see-through eye), `xray on|off`, `see on|off`, `seed N`, `dump`, `evidence`.

## Known
- 3D taps cannot be injected on the Mac emulator (only the 2D window publishes nodes): scene input was driven by the debug
  broadcast through the same `route()`. Controller ray / hand ray / eye-hand on the 5 cm pegs and the rim push: headset only.
- The system reports a drag's travel, not where it started: the push assumes the grab point is the rim's front edge
  (`Turntable.dragAngle`), so pushing the front edge sideways turns the table.
- Sounds are SoundPool (not spatial).
