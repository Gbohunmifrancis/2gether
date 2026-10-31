# Heartpact Plus pricing (October 2026)

**Decision:** $7.99 a month, $39.99 a year (about $3.33 a month, 58% less than paying monthly), with a 7-day free trial
on the yearly plan only. One partner's subscription covers both. Prices are set in US dollars; Play converts them,
and a few markets get lower prices by hand (below).

## What comparable apps charge

Taken on 2026-10-31 from App Store purchase lists and the apps' own help pages. Google Play pages don't list
in-app prices. Most listings carry several prices for one plan (price tests), so these are ranges.

| App | Monthly | Yearly | Trial | One plan covers both |
|---|---|---|---|---|
| Paired | $14.99 | $39.99–$74.99 (promos $19.99–$29.99) | 7 days | Yes |
| Lasting | $11.99–$14.99 | about $79.99 | 7 days | Yes |
| Agapé | $9.99–$14.99 | $19.99–$99.99 | 7 days (uncertain) | Uncertain |
| Flamme | $12.99 | $44.99 (promo $19.99) | yes, length unknown | Not stated |
| Candle | $12.99 | $39.99–$49.99 | not stated | Not stated |
| Coral | $12.99 | $39.99–$89.99 | not stated | Uncertain |
| Lovewick | $9.99 | $29.99 | not stated | Not stated |
| Evergreen | about $9.99–$12.99 | about $49.99 | not stated | Not stated |
| Cupla (shared calendar) | $4.99 | $44.99 | 14 days, yearly only | Yes |
| Between (chat and album) | $2.99 | $24.99 | not stated | Yes |
| Strava (fitness, for scale) | $11.99 | $79.99 | varies | Family plan |

The relationship apps cluster at $9.99–$14.99 a month and $39.99–$49.99 a year. A yearly plan costs about 3–4
months' worth (a 65–75% saving). Seven days is the usual trial, often on the yearly plan only. Utility apps (calendar,
chat) sit lower, at $3–$5 a month.

## Why these numbers

- **Plus is lighter than a typical paywall.** Competitors lock games, unlimited questions and programs behind
  theirs. Heartpact keeps dares, camera counting, questions, games, cycle and location free, and Plus adds more food
  scans, album storage and question history. So the monthly price sits below the $9.99–$14.99 cluster.
- **The yearly plan is the one to sell.** $39.99 is at the market's centre and below Paired's, Candle's and Flamme's
  list prices. At $7.99 a month, a year costs $95.88, so the yearly plan shows a clear 58% saving, and the paywall
  puts yearly first.
- **The trial goes on the yearly plan only.** That's where the conversion matters, and it matches the norm.
- **One plan covers both.** Only Paired, Lasting, Cupla and Between say this outright, so the site and paywall say it
  plainly.

## Regional prices (set by hand in Play Console)

Play converts the US price at exchange rates, not purchasing power. Paired and Cupla charge about 40–60% below the
converted price in India. Suggested starting points, to adjust once there's conversion data:

| Market | Monthly | Yearly |
|---|---|---|
| India | ₹299 | ₹1,499 |
| Brazil | R$19.90 | R$99.90 |
| Mexico | MX$79 | MX$399 |
| Other lower-income markets | about half the converted price | about half the converted price |

## In Play Console

- `heartpact_plus_monthly`: base plan, auto-renewing monthly, $7.99.
- `heartpact_plus_yearly`: base plan, auto-renewing yearly, $39.99, plus an offer "7-day free trial" for new
  customers.
- Admin revenue estimate (API settings): `Admin__MonthlyPrices__heartpact_plus_monthly=7.99`,
  `Admin__MonthlyPrices__heartpact_plus_yearly=3.33`.
