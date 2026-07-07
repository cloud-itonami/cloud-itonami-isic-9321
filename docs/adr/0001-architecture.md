# ADR-0001: cloud-itonami-isic-9321 -- ParkOps-LLM as a contained intelligence node

- Status: Accepted (2026-07-07)
- Related: `cloud-itonami-isic-6511`/`6512`/`6621`/`6622`/`6629`/`6520`/
  `6530`/`6820`/`6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/
  `7500`/`9603`/`9521` ADR-0001s (the pattern this ADR ports);
  ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819/ADR-2607071849 (`6612`/`6492`/`6920`/
  `6611`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/`9521`, the eleven
  verticals built outside ADR-2607032000's original insurance/real-
  estate batch -- this is the twelfth)
- Context: Continuing the standing "pick a new ISIC blueprint
  vertical" direction past `9521`, this ADR deepens `cloud-itonami-
  isic-9321` (activities of amusement parks and theme parks) from
  `:blueprint` to `:implemented`, the twentieth actor in this fleet --
  the FIRST leisure-attractions vertical (ISIC division 93).

## Problem

An amusement park's ride-reopening workflow bundles several distinct
concerns under one governed workflow:

1. **Jurisdiction ride-safety correctness** -- an official spec-basis
   citation from a real ride-safety regulator (MLIT/Cal-OSHA/HSE/state
   trade-supervisory authorities), never fabricated.
2. **Post-hold inspection verification** -- has a ride actually passed
   its post-hold inspection before being reopened to patrons? The
   leisure-attractions-specific reuse of the unconditional-evaluation
   screening discipline this fleet's `casualty.governor/sanctions-
   violations` originally established -- a NINTH distinct grounding.
3. **Operator-staffing sufficiency** -- does a ride's own certified-
   operator headcount actually satisfy its own minimum staffing
   requirement? Reuses this fleet's MINIMUM-threshold pure-ground-
   truth-recompute shape (`marketadmin.governor`'s/`registrar.
   governor`'s shape), but the FIRST instance to compare TWO fields on
   the SAME entity rather than one field against a shared constant.
4. **Real, safety-critical actuation, once** -- reopening a ride to
   patrons after a safety hold is a single actuation event with direct
   physical-safety stakes for the public.

An LLM has no authority or grounding for any of these. The design
problem is therefore not "run an amusement park with an LLM" but
"seal the LLM inside a trust boundary and layer evidence-sufficiency,
post-hold-inspection verification, operator-staffing verification,
audit and human-approval on top of it, while structurally fixing the
one real actuation event as human-only."

## Decision

### 1. ParkOps-LLM is sealed into the bottom node; it never reopens directly

`parksafety.parkopsllm` returns exactly four kinds of proposal: intake
normalization, jurisdiction ride-safety checklist, post-hold
inspection screening, and ride-reopening draft. No proposal writes the
SSoT or commits a real ride reopening directly.

### 2. OperationActor = langgraph-clj StateGraph, 1 run = 1 amusement-park operation

`parksafety.operation/build` is the SAME StateGraph shape as every
sibling actor's operation namespace, copied verbatim.

### 3. Post-hold inspection screening reuses the unconditional-evaluation discipline for a ninth distinct grounding

`inspection-not-passed-violations` reuses `casualty.governor/
sanctions-violations`'s fix (evaluated unconditionally, not scoped to
a specific op, so the screening op itself can HARD-hold on its own
finding) for BOTH `:inspection/screen` and `:ride/reopen` -- the NINTH
distinct application of this exact discipline in this fleet.

### 4. `operators-sufficient?` generalizes the MINIMUM-threshold family to a two-field-on-one-entity comparison

`operators-insufficient-violations` reuses `marketadmin.governor/
listing-standard-not-met-violations`'s/`registrar.governor/credits-
not-sufficient-violations`'s MINIMUM-threshold pure-ground-truth-
recompute shape, but is the FIRST instance in this fleet to compare
TWO permanent fields on the SAME entity (`:certified-operators-on-
duty` against the ride's own `:minimum-operators-required`) rather
than one field against a single shared constant -- a real, honest
generalization, since different rides genuinely require different
minimum staffing levels (not a single park-wide figure).

### 5. A REAL bug WAS caught during test verification -- a test/demo design error, not a governor logic bug

The initial `inspection-not-passed-is-held` test called `:ride/reopen`
directly (with only a prior `:jurisdiction/assess` on file) expecting
the HOLD to fire from `ride-3`'s ground-truth `:post-hold-inspection-
passed? false` field. It did not -- the test failed with `:escalate`
instead of `:hold`. The root cause: the unconditional-evaluation check
family (established across all nine groundings in this fleet) fires
ONLY when either (a) the CURRENT proposal itself carries a bad verdict
(relevant when the request IS the screening op) or (b) a PRIOR
screening commit persisted a bad verdict to the store -- and a failing
screen is itself a HARD hold, so it NEVER actually commits its payload
to the store. This means the actuation op alone, without the screening
op ever having been run, cannot discover a bad ground-truth flag
through this check family at all -- by design, matching EVERY prior
sibling's test suite, which invariably tests this check family via the
SCREENING op directly (e.g. `clinic.governor-contract-test/credential-
not-current-is-held-and-unoverridable`, `veterinary.governor-contract-
test`'s equivalent), never via the actuation op with an un-screened
ground truth. **The lesson this ADR records**: the unconditional-
evaluation check family is a "was a finding flagged, currently or
previously" check, NOT an independent ground-truth recompute (unlike
`operators-insufficient-violations`/`parts-cost-mismatch-violations`/
etc., which DO independently recompute) -- exercising it in a test or
demo requires invoking the SCREENING op itself, not the actuation op
against an unscreened entity. Fixed by rewriting the test (now
`inspection-not-passed-is-held-and-unoverridable`, testing `:
inspection/screen` directly, mirroring every sibling's pattern
exactly) and the demo (`parksafety.sim` now screens `ride-3` directly
rather than attempting to reopen it unscreened).

### 6. Single actuation event

`parksafety.governor`'s `high-stakes` set has exactly one member
(`:actuation/reopen-ride`), matching `6511`'s/`6621`'s/`6629`'s/
`6612`'s/`6492`'s/`7120`'s/`8620`'s/`7500`'s/`9603`'s single-actuation
shape -- this domain has one distinct real-world, safety-critical act
(reopening a ride), not several independently-gated acts.

### 7. Double-reopening guard checks a dedicated boolean fact, not `:status`

`already-reopened-violations` checks `:reopened?`, a dedicated boolean
set once and never cleared, rather than a `:status` value that could
legitimately advance past a checked state (the exact trap `cloud-
itonami-isic-6492`'s ADR-0001 documents in detail, explicitly avoided
BY DESIGN in every sibling actor's equivalent guard since). This
actor's `:status` never needs to encode "has this actuation already
happened" at all -- a deliberate architectural choice applied here for
a tenth consecutive time.

### 8. No bespoke capability lib

Like `6920`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/`9521`, and
unlike most other actors in this fleet, this vertical's operational
records are practice-specific rather than a shared cross-operator data
contract -- `parksafety.*` runs on the generic identity/forms/dmn/
bpmn/audit-ledger stack only, per the blueprint's own explicit
statement.

## Consequences

- (+) Amusement-park/ride-safety gets the same governed, auditable-
  actor treatment as the nineteen prior actors, extending the pattern
  to a genuinely different domain (leisure attractions, ISIC division
  93) for the first time.
- (+) The actuation invariant (governor + phase, two layers) is
  regression-tested by `test/parksafety/phase_test.clj`'s `ride-
  reopen-never-auto-at-any-phase`.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by `test/
  parksafety/store_contract_test.clj`, the same `:db-api`-driven swap
  pattern every sibling actor uses.
- (+) `operators-sufficient?`/`operators-insufficient-violations`
  extends this fleet's minimum-threshold family to a genuine new
  shape: a two-field-on-one-entity comparison, rather than a field-
  vs-shared-constant comparison.
- (+) The test/demo bug (Decision 5) was caught by the SAME discipline
  that has caught every real bug in this fleet -- running the full
  test suite and independently verifying the demo ledger, not assuming
  correctness -- and its root cause (a structural property of the
  unconditional-evaluation check family, not a logic error in the
  governor itself) is now explicitly documented so future builds don't
  make the same test-design mistake.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `parksafety.facts/
  coverage` reports this honestly rather than claiming broader
  coverage.
- (-) `operators-sufficient?` models only a certified-operator-
  headcount-vs-minimum comparison, not a full ride-safety engineering
  program (structural-fatigue analysis, ride-specific engineering
  inspection criteria, patron-load/capacity modeling are out of scope
  -- see that fn's own docstring); real park-operations-system
  integration and ongoing ride-maintenance-scheduling are all out of
  scope for this OSS actor -- each operator's responsibility (see
  README's coverage table).
- 29 tests / 127 assertions, lint clean (after the test-design fix).

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Add this as an addendum to any prior post-batch ADR | ❌ | All eleven of those ADRs' titles and scopes are explicitly `cloud-itonami-isic-6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/`9521`; mixing a different ISIC division (93, distinct from those eleven's divisions) into any would blur scope boundaries |
| Keep `cloud-itonami-isic-9321` at `:blueprint` only | ❌ | The standing direction continues past `9521`; amusement parks are a natural, well-precedented next domain, further diversifying this fleet into leisure attractions (division 93) |
| Change `inspection-not-passed-violations` to independently recompute from `:post-hold-inspection-passed?` directly, bypassing the proposal/stored-verdict check entirely | ❌ | Would deviate from the unconditional-evaluation discipline all nine prior groundings establish, and would blur the useful distinction this fleet maintains between "screening findings" (proposal/store-verdict-based) and "pure ground-truth recomputes" (direct-field-based, like `operators-insufficient-violations` itself) -- fixing the TEST to match the established pattern is the correct fix, not changing the pattern |
| Model a full ride-safety engineering program for conformance-test rigor | ❌ | Genuinely more complex real-world mechanical-safety-engineering logic that this R0 does not claim to model correctly -- honestly scoped to a certified-operator-headcount comparison instead, same as every sibling's "starting catalog, not exhaustive" posture |
| Reference a capability lib (e.g. a hypothetical `kotoba-lang/amusement`) for consistency with most prior actors | ❌ | The blueprint itself explicitly states this vertical's records are practice-specific, not a shared cross-operator contract -- inventing a capability lib reference where the blueprint says none exists would misrepresent the domain, the same reasoning established by every "no bespoke capability lib" sibling's ADR |
