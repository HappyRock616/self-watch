# WP-09 ui (theme, components, charts)

Lane C. Milestones M0 (theme, base components), M2 (charts). Android library. Depends on: core-model, appcompat, material, recyclerview, core-ktx.

## Goal

A small, consistent design kit in the 5K style (minimal, strong visual cues; see the memory note on design preferences). Plus Self Watch's signature: the **overlay chart** that layers related data (sleep stages with heart rate, HRV and breathing) with toggle chips. All Android Views, no Compose, no chart library.

## Theme (`res/values/`, `values-night/`)

| Token | Dark (default) | Light |
|---|---|---|
| `colorPrimary` / `colorSecondary` | the color theme's primary / secondary (below) | the same, darkened to 3:1 |
| background | `#0B0B0B` | `#F7F6F2` |
| surface (cards) | `#16324A` navy | `#FFFFFF` |
| surface variant | `#1F4766` | `#E9EEF2` |
| on-surface text | `#F1EFE8` | `#16202A` |
| muted text | `#BACAD6` | `#52616D` |
| track (rings, progress bars) | `#3A3A38` | `#E2E1DB` |
| chart series, sleep stages | from the color theme, see **Chart palette** | |

- **Base theme:** `Theme.Material3.DayNight.NoActionBar`. The color theme is user-selectable, as a theme overlay per theme.
- **Type:** system font (Roboto or the phone's); sizes 12, 14, 16, 20, 28, 40 sp. Numbers use tabular figures (`fontFeatureSettings="tnum"`).
- **Shapes:** cards with 16 dp corners, chips with 12 dp; 8 dp grid.
- **Edge-to-edge:** `WindowCompat.enableEdgeToEdge(window)` in a base activity. Every root container uses `InsetsPadding` (below).

### Color themes (`SwTheme.Theme`)

The owner's decision: the themes are the five of the Just Go 5K watch app (`JustGo5K/source/Theme.mc`), same names,
order and colors. People pick **one theme** (Me › Settings › Color theme, shown as primary + secondary swatch pairs),
never single colors, and it colors every screen and every chart. Light / Dark / System is a separate setting.

| Theme | Primary | Secondary | Text on primary | Text on secondary |
|---|---|---|---|---|
| Turquoise | `#19D3C5` | `#FFD84D` | `#04342C` | `#412402` |
| Purple | `#BD93F9` | `#7CC8FF` | `#26215C` | `#042C53` |
| **Orange** (default) | `#FF8A1F` | `#4DABF7` | `#412402` | `#042C53` |
| Yellow | `#F5B841` | `#2E9E5B` | `#412402` | `#092012` (watch: `#E8F5EC`) |
| Forest | `#2E9E5B` | `#F5B841` | `#092012` (watch: `#E8F5EC`) | `#412402` |

Red `#FF453A` is reserved for destructive, alarm and error states in every theme. Neutrals follow Just Go 5K
(background `#0B0B0B`, text `#F1EFE8`, muted `#B4B2A9`, track `#3A3A38`); the card surface stays navy.

- `ThemeColors` works out every role from the two theme colors (one algorithm, JVM-tested): fills are the theme
  color mixed towards black (light look) or white (dark look) only until they reach 3:1 on the surface and the
  background, so the dark look uses the exact Just Go 5K colors; text on a fill is the watch's text color when it
  reaches 4.5:1, otherwise a dark shade of the fill's hue (Forest and Yellow's green: the watch's light text is
  3.1:1, too low for phone text sizes); `swColorAccentText` is the primary as small text (4.5:1 on background,
  surface and surface variant).
- `themes.xml` has one overlay per theme (`ThemeOverlay.SelfWatch.Theme.*`) setting `colorPrimary`,
  `colorOnPrimary`, `colorSecondary`, `colorOnSecondary`, `colorTertiary` (= secondary), `colorPrimaryInverse`,
  `swColorAccentText` and `swThemeId` (the enum ordinal). The `sw_theme_*` colors in `colors.xml` are generated
  from `ThemeColors`; `ThemeColorsTest` fails if they drift.
- Storage: the `accent` setting holds the theme's primary (`#FF8A1F` = Orange, the old default). Old accent values
  migrate once at start: orange to Orange, blue to Turquoise, green to Forest, violet to Purple.
- Live: `SwTheme.setCurrent` (called by the app when the setting changes) makes every started `SwActivity`
  recreate at once, and stopped ones when they come back. No notification sets an accent color.

### Chart palette (`ChartPalette`)

The one place charts, rings, bars, sparklines, toggle chips, tags and card accents get colors. `ChartPalette.of(context)`
reads the context's theme overlay (`swThemeId`) and night mode; `ChartPalette.of(theme, night)` for tests.

- Series 1 = primary, series 2 = secondary. Series 3 / 4 = tints of primary / secondary (dark: 45% towards white;
  light: 35% towards black). Series 5 / 6 = shades (dark: 35% towards black, pulled back to 3:1; light: 60% towards
  black). Then the list repeats. Every series reaches 3:1 on surface and background, and series are at least 24 apart
  in RGB distance (tested for every theme and look).
- Assignment: a card or chart's main metric is series 1, a second metric series 2, then in chip order. "On target"
  is primary, "not yet" secondary, "over a limit" red. Toggle chips take their layer's color (text: black or white).
- Sleep stages: REM = primary; deep = the darker secondary, light = the lighter secondary (dark look: secondary and a
  45% white tint; light look: secondary 45% towards black and secondary); awake = neutral gray; unmeasured = track.
- Neutrals: `text`, `muted` (axis labels, usual bands, weight dots, goal lines), `track`. `alarm` = red, never a
  series. `primaryText` / `secondaryText` for labels in a series color. Tags: database = neutral pill, mine and
  meals = primary tint, chain = secondary tint.
- No chart or card code holds a fixed series color; `R.color.sw_series_*` and `sw_stage_*` no longer exist.

## Components (`app.selfwatch.ui.components`)

| Class | What it is | Key properties |
|---|---|---|
| `InsetsPadding` | Helper applying system bar, cut-out and keyboard insets as padding | `apply(view, top, bottom, ime)` |
| `CardView` style + `SwCard` | Navy card with title row and optional action | `title`, `actionText`, `onAction` |
| `StatTile` | Big number + unit + label + change arrow | `value`, `unit`, `label`, `delta`, `deltaGoodWhenDown` |
| `RingView` | Progress ring (goal ring) with center text; primary on the track gray by default | `progress 0..1`, `color`, `centerText`, `subText` |
| `ThemeSwatch` | A theme's primary + secondary swatch pair (drawable, or a list label) | `drawable(ctx, theme)`, `label(ctx, theme, name)` |
| `BarsView` | Small bar chart, e.g. 7-day in vs out | `series: List<BarSeries>`, `labels`, `goalLine` |
| `Sparkline` | Tiny line for list rows | `values: IntArray`, `color` |
| `ToggleChipGroup` | Material chips that toggle series on and off; remembers state per chart id | `items`, `onChange` |
| `ListRow` | Two-line row with trailing value and chevron | `title`, `subtitle`, `trailing`, `onClick` |
| `SourceTag` | Small pill: USDA, OFF, Chain 2018, Mine, Meal | `text`, `style` |
| `EmptyState` | Icon + one sentence + button | |
| `SwSheet` | Bottom sheet base (Add sheet, food detail, quick add) | `BottomSheetDialogFragment` subclass |
| `NumberStepper` | Amount entry with − / + and keyboard | `valueX100`, `step`, `unit` |
| `SwSnackbar` | Undo messages ("Deleted. Undo") | |

## Charts (`app.selfwatch.ui.charts`)

### `OverlayChartView` (custom `View`)

```kotlin
class OverlayChartView(ctx: Context, attrs: AttributeSet?) : View(ctx, attrs) {
    var range: TimeRange
    var layers: List<ChartLayer>                 // drawn in order: bands/stages first, then bars, then lines
    var onScrub: ((tSec: Long, values: List<LayerValue>) -> Unit)?
    fun setVisible(layerId: String, visible: Boolean)
}
sealed interface ChartLayer { val id: String; val label: String; val color: Int; val axis: Axis }
data class LineLayer(override val id: String, override val label: String, override val color: Int, override val axis: Axis,
                     val points: LongArray /* tSec */, val values: IntArray, val scale: Int /* divide for display */,
                     val smooth: Boolean = false, val gapSec: Long = 600) : ChartLayer
data class BarLayer(…, val bucketSec: Long, val values: IntArray) : ChartLayer
data class StageLayer(…, val spans: List<StageSpan>) : ChartLayer         // sleep stages as colored blocks, each stage its own row height
data class BandLayer(…, val low: IntArray, val high: IntArray) : ChartLayer  // min-max ranges for week and month views
enum class Axis { LEFT, RIGHT, HIDDEN }       // up to two visible scales; others are drawn normalized with their own label on scrub
```

**Drawing:**
- `onDraw` uses precomputed `Path`s, rebuilt only when data or size changes.
- Down-sampling: at most 2 points per pixel per layer (min and max per pixel column), so a night of 1-second heart rate stays smooth.
- Gaps longer than `gapSec` break the line.
- Axis labels: 3–5 ticks, nice numbers. The time axis uses the phone's 12/24-hour format.

**Interaction:**
- Touch and drag shows a vertical scrub line plus a bubble listing every visible layer's value at that time.
- Double tap zooms 2× around the point; pinch zooms; pan when zoomed.
- `onScrub` lets the screen show values in a fixed panel instead.

**Accessibility:** `AccessibilityNodeProvider` exposing about 12 time slices as virtual views ("2:00 am: deep sleep, heart rate 52, HRV 61 ms"), plus a "Read as table" action that opens a simple list.

**Performance:** under 4 ms per frame on the Pixel 5 for a night (8 h × 1 Hz heart rate + stages + HRV + breathing). Checked with a benchmark test in T-C08.

**Measured on Pixel 5** (2026-11-23, `ChartBenchmarkTest`, `./gradlew :ui:connectedDebugAndroidTest`): a 904 × 715 px chart with 28,560 heart-rate samples, 27 stage spans, 96 HRV and 480 breathing points, drawn into a Bitmap-backed (software) Canvas, 300 frames after 60 warm-up frames:

| Frame | Median | p90 |
|---|---|---|
| Static (cached paths) | 1.00 ms | 1.07 ms |
| Scrubbing (line, dots, bubble) | 1.84 ms | 2.14 ms |
| Zoom or pan (paths rebuilt: down-sample + build) | 2.43 ms | 2.97 ms |

Software drawing is slower than the hardware pipeline the screen uses, so these are upper bounds. Two choices got it there: straight lines are drawn as one anti-aliased hairline per pixel column spanning the line's extent in that column (`ChartMath.envelope`) instead of stroking a 1,800-point path (that alone took 7.5 ms), and down-sampling is a tight loop without closures (3.2 → 0.46 ms for a night). `smooth = true` lines are still stroked and cost more.

**Implementation notes (C07, C09):** one-finger drag scrubs (it claims the gesture only once the drag is mostly horizontal, so a page still scrolls); pinch zooms and a two-finger drag pans; double tap zooms 2× and, at the closest zoom, resets. Time runs left to right in right-to-left locales too (like a clock); bubble text follows the locale. With stages and other layers visible, stages take the top half and the rest share the bottom half. `TrendChartView` and `WeightTrendView` follow the same rule. Sample data generators for the gallery and benchmark live in `ui/src/debug` (`ChartSamples`), so they never ship.

### Other charts

- `TrendChartView`: bars per day, week or month with a goal line and average. Used by Activity and Weight.
- `WeightTrendView`: dots for entries and a smoothed trend line (exponential moving average, 10-day half-life), with a goal line.

## Formatting (`app.selfwatch.ui.format`)

`Fmt.energy(kcal)`, `Fmt.weight(kgX100, units)`, `Fmt.length(cm, units)`, `Fmt.distance(m, units)`, `Fmt.duration(sec)`, `Fmt.time(tSec)`, `Fmt.day(day)` ("Today", "Yesterday", "Sat 22 Nov"), `Fmt.percent`, `Fmt.number`.

All locale-aware (`NumberFormat`, `DateTimeFormatter.ofLocalizedTime`), with plurals from resources. Units from the `units` setting, or the locale when it's `phone` (US, Liberia and Myanmar use imperial).

## Compatibility checklist

COMPATIBILITY §6 in full.
- [ ] Every component looks right at 200% font in a 320 dp width.
- [ ] Dark and light.
- [ ] Right-to-left layout preview.
- [ ] TalkBack labels.
- [ ] Insets on API 35+ with gesture and 3-button navigation.

## Tests and definition of done

- [ ] Robolectric screenshot-free tests for the down-sampling, scale and tick maths, and scrub value lookup.
- [ ] A component gallery activity (debug builds only) showing every component in both looks, all 5 color themes (with each theme's chart palette) and at 3 font sizes. HappyRock reviews the look here before screens are built (T-C04).
- [ ] Chart benchmark (T-C08).
