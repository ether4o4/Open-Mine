Source visuals: `app/src/main/assets/hud/reference-{models,projects,connectors,knowledge}.jpg` supplied by the user.

Implementation: the existing Open Mine Android application, using native Jetpack Compose text and controls. Application commit: `de332a93a686daf979eba7a396383063512db48b`.

Capture method: `HudInteractionTest` on an API 29 Pixel 2 emulator. PNG captures are taken from the actual app root after composition and rendering settle. The GitHub Actions artifact contains original screenshots and instrumentation output.

Viewport and state: source models 693×1280, connectors 752×1280, knowledge 748×1280. The project reference includes a second-screen fragment; only the first 684×1196 screen is used. Native captures are 1080×1731. The centered 700×1280 artboard is extracted from the letterbox and both inputs normalized to 700×1280. Each category shows its first object, Overview, empty context, and animations disabled. Clock/date are live.

Comparison images place the reference on the left and the native render on the right:

- [AI Models](docs/hud-qa/models-comparison.jpg)
- [Projects](docs/hud-qa/projects-comparison.jpg)
- [Connectors](docs/hud-qa/connectors-comparison.jpg)
- [Knowledge](docs/hud-qa/knowledge-comparison.jpg)

Comparison history and fixes:

- Initial native capture: dark atlas mattes, thin card frames, different type proportions, an oversized detail text column, and a dark action button. Fixed with luminance-based artwork opacity, glass/metal frame artwork, bundled Ubuntu fonts, adjusted typography/insets, and a brighter cyan gradient action.
- First refinement: Screen blending alone retained mattes inside compositor layers. Replaced it with an alpha color matrix. Supplied central symbols, detail images, and carousel artwork now retain their source appearance.
- Layout refinement: restored the missing left-side cards. Models and Projects each have nine orbital cards; Connectors and Knowledge each have eight. All three device tests passed at commit `a32e161cf06c2d62b7cec114c95be30ea5403ad9`.
- Final artwork refinement: reused supplied orbital and dock symbols, including the note, music, relationship, device, folder, stone cube, skill diamond, and mission globe artwork.

Fidelity surfaces checked: typography hierarchy and wrapping; rail, orbit, panel, carousel, and dock spacing; dark glass/cyan/violet colors; image clarity and matte edges; native titles, tags, and body copy. Controls remain selectable native elements rather than a screenshot overlay.

Intentional product differences: the app is branded Open Mine. Location describes private on-device storage, the clock/date use the device, and status reflects local connector configuration. Project/model catalogue copy is coherent with the existing app; ambiguous repeated reference labels are disambiguated. Provider selection does not establish live connections or run inference.

Known visual limits: generated map/ring texture and several model/provider emblems differ from the reference pixels. Frame reflection, glow intensity, and font rendering also differ from the photographed mockups. The portrait composition is fitted and letterboxed on other aspect ratios. This implementation does not claim pixel identity.

Validation: Kotlin compilation, debug APK assembly, and all three native device tests passed for the final application commit. Successful run: https://github.com/ether4o4/Open-Mine/actions/runs/37501452978 (attempt 2). Instrumentation output: `OK (3 tests)`, 13.124 seconds. The test covers primary views/dock, selection/context persistence and removal, and vault/diagnostics/settings access. The first attempt timed out while Google's SDK manifest service returned download/XML errors; the retry succeeded.

Final screenshot review: all four full-screen combined images and detail crops were inspected. The missing cards are restored, native controls are visible, titles/tags/body copy fit, the dock artwork matches the supplied symbols, and the major matte squares are removed. The corrected portrait composition and functional flows pass. Remaining P3 differences include texture/glow/reflection, several generated emblems, small crop-edge traces, and native font rendering. No pixel-identity claim is made.

Implementation checklist: final APK built; three tests passed; four native screens captured; full-screen and detail comparisons inspected; review evidence saved; existing vault preserved; Open Mine branding retained.

final result: passed
