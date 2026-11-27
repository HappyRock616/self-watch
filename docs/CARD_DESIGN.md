# Card design standard

Approved by HappyRock on 2026-11-26 (mockups: `docs/card_design.html`). Inspired by Gadgetbridge's dashboard
gauges and charts and by Pulse's Today screen. Applies to every data card on Body, Activity and Food.

## Rules

1. **One picture per card.** A ring, arc gauge, range bar, week bars, day strip, split bar or trend line.
   The picture is the card.
2. **The number lives on the picture.** Inside the ring or gauge, or at the end of the line. One big
   number and its unit.
3. **Four words, no sentences.** Title plus at most one short tag ("Low", "7-day avg 62"). No
   explanations, notes or advice on the card: those belong on the detail screen.
4. **Color is the meaning.** Garmin's colors for levels (`LevelColors`): stress rest blue to high orange;
   sleep stages from `ChartPalette`; goal met = `good` (primary), short = `close` (secondary), a range to
   watch = `off`.
5. **Every chart is readable.** Week bars have day letters and a goal line. Day strips have 6 / 12 / 18.
   Trend lines label both ends. No unlabeled sparklines.
6. **Small cards: number and picture only.** Give the small form a picture with
   `createSmallGraphic`/`bindSmallGraphic`; a gauge or ring that shows the number sets
   `smallGraphicShowsValue = true`. A picture hides the note line and the progress bar automatically.
   Keep `SmallFace.value` and `label` (TalkBack reads them).
7. **Tap for the detail.** Every card opens its detail screen.
8. **Chart cards.** The overlay charts with toggle chips (Sleep, Heart, Weight, Activity) can be placed on
   the tabs as large cards, unchanged.

## Building blocks (`ui/src/main/kotlin/app/selfwatch/ui/components/`)

| View | Use | Key properties |
|---|---|---|
| `RingView` | goal progress | `progress`, `color`, `centerText`, `subText` |
| `RingRowView` | a row of up to 6 goal rings (Body today, Activity goals) | `items: List<RingItem(progress, color, value, label)>` |
| `ArcGaugeView` | 0–100 scores (stress, energy, sleep score, readiness) | `bands: List<Band(upTo, color)>`, `min`, `max`, `value`, `centerText`, `subText` |
| `RangeBarView` | where a reading sits among named ranges (BMI, BP, body fat, VO2, HRV baseline) | `bands`, `min`, `max`, `value`, `ticks: List<Pair<Float, String>>` |
| `BarsView` | week bars with a goal line | `series`, `labels`, `goalLine`, `barColor = { group, value -> color? }` |
| `DayStripView` | 24 hours in slots (stress, energy, steps) | `values: IntArray` (−1 none), `max`, `colorFor`, `dim: BooleanArray`, `marks` |
| `SplitBarView` | time or amount split (sleep stages, stress levels, resting vs active kcal) | `parts: List<SplitPart(value, color, label)>`, `mark` |
| `TrendLineView` | a trend with labeled ends (weight, resting HR, VO2) | `values: FloatArray`, `color`, `startLabel`, `endLabel`, `band`, `goal` |
| `DotsView` | day dots (active days, strength sessions, streaks) | `filled: BooleanArray`, `labels`, `color` |

Shared helpers in `app/.../cards/CardVisuals.kt`: `stressBands`, `scoreBands(edges)`, `smallGauge`, `smallRing`,
`minutes`, `show`. Sizes in `app/src/main/res/values/dimens.xml` (`card_gauge_height`, `card_ring_height`, …).
Garmin band names and edges: `app/.../body/GarminBands.kt`.

## Reference card

`app/src/main/kotlin/app/selfwatch/body/cards/BodyStressCard.kt` with `res/layout/card_body_stress.xml`:
gauge (today's average, Garmin level), day strip (15-minute stress, faded asleep), split bar (time per level),
small form = gauge. Copy its structure.

## Per-card pictures

See the table in `docs/card_design.html` ("Every card"). Waiting cards keep their one-line message.
