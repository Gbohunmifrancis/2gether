# Heartpact 3D icons and splash (Rive)

Thirteen glossy, extruded icons in the app's colours, and the 3D splash, built as one Rive file with the
[Rive CLI](https://rive.app/docs/cli/getting-started). Each icon is its own 200×200 artboard, named:

| Tabs | Features |
|---|---|
| `today`, `pacts`, `food`, `us`, `album` | `cycle`, `plus`, `games`, `dares`, `location`, `question`, `bell`, `pregnancy` |

`splash` (400×400, `splash.rml`, from `tools/build_splash.py`) is the launch moment on the app's splash timeline
(`SplashTimeline` in `android/.../ui/splash/Splash.kt`), so the love sound still lands on its heartbeats: the two
halves hop in with their faces, lean together, close their eyes and join; the tick draws on, the eyes open, the heart
beats twice and blinks. It is transparent; the app draws the indigo background and the wordmark.

`Sheet` shows them all at 1.5× on the page colour, for looking at; the app never loads it.

## How they move

Every artboard plays the state machine `Icon`, driven by one view model, `Icon`, with a boolean `active`:

- **Idle** (resting): floats up and down with a slight sway, its shadow shrinking as it rises. A 3-second loop.
- **Pop** (when `active` turns true): squashes, jumps, wobbles and lands a little bigger. About half a second.
- **Active** (after the pop): a livelier bob at the bigger size, until `active` turns false. It then eases back to Idle.

The app sets `active` on the selected tab's icon and clears it on the others.

## How they're made

`tools/build_icons.py` describes each icon as flat parts in a 100×100 design space: SVG paths, rounded rectangles,
ellipses, polygons and stroked lines, listed front to back, each with a colour. `tools/rml3d.py` turns every part into
the 3D look, and the build script writes `icons.rml`:

- **Face:** a gradient lit from the top left.
- **Side:** copies of the outline stepped down and right, darkening toward the back.
- **Gloss:** a highlight on the larger parts, clipped to the face.
- **Shadow:** an oval under the icon, a radial gradient squashed flat.

No feathers, scripts or images, so the file renders the same in every runtime and needs no signing; with the splash
it is about 115 KB.

The colours are the app's tokens (`DESIGN.md`): navy `#1F2557`, indigo `#4D58BE`, periwinkle `#7480D4`, soft
`#D5D8F6`, white, and the heart pink `#F45C91`.

## In the app

`android/app/src/main/res/raw/heartpact_icons.riv` is this project's build, and
`res/drawable-nodpi/icon3d_<name>.webp` are the stills (`tools/export_stills.py`: each icon at rest, rendered on black
and on white to recover its transparency, 256 px). The app plays only the selected tab in Rive; the other tabs, the rows
and anywhere Rive can't run use the stills. See `ui/graphics/Icon3D.kt`.

## Building

```bash
curl -fsSL https://releases.rive.app/cli/install.sh | sh     # once; on Linux it also needs libegl1, libgles2, libx11-6
python3 tools/build_icons.py                                  # writes icons.rml and splash.rml
~/.rive/bin/rive . --verify && ~/.rive/bin/rive inspect . --summary
~/.rive/bin/rive . --once                                     # build/heartpact_icons.riv
cp build/heartpact_icons.riv ../../android/app/src/main/res/raw/  # into the app
python3 tools/export_stills.py                                # the stills, into the app
~/.rive/bin/rive . --screenshot=build/sheet.png --advance=1   # every icon, as a png
~/.rive/bin/rive .                                            # live preview window, rebuilds on save
```

To look at one icon, set `main:` in `rive.yaml` to its name. Add `--data=active=true --advance=40` to a
`--screenshot` to see it popped. To change an icon, edit its parts in `tools/build_icons.py` and rebuild; don't edit
`icons.rml` by hand, since the build script overwrites it.
