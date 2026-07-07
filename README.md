# cloud-itonami-isic-9321

Open Business Blueprint for **ISIC Rev.5 9321**: Activities of
amusement parks and theme parks. This repository publishes an
amusement-park/ride-safety actor -- ride intake, jurisdiction
assessment, post-hold inspection screening and ride reopening -- as an
OSS business that any qualified, licensed park operator can fork,
deploy, run, improve and sell.

Built on this workspace's
[`langgraph-clj`](https://github.com/com-junkawasaki/langgraph-clj)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, Datomic/in-mem checkpoints) -- the same actor pattern as
every prior actor in this fleet
([`cloud-itonami-isic-6511`](https://github.com/cloud-itonami/cloud-itonami-isic-6511),
[`6512`](https://github.com/cloud-itonami/cloud-itonami-isic-6512),
[`6621`](https://github.com/cloud-itonami/cloud-itonami-isic-6621),
[`6622`](https://github.com/cloud-itonami/cloud-itonami-isic-6622),
[`6629`](https://github.com/cloud-itonami/cloud-itonami-isic-6629),
[`6520`](https://github.com/cloud-itonami/cloud-itonami-isic-6520),
[`6530`](https://github.com/cloud-itonami/cloud-itonami-isic-6530),
[`6820`](https://github.com/cloud-itonami/cloud-itonami-isic-6820),
[`6612`](https://github.com/cloud-itonami/cloud-itonami-isic-6612),
[`6492`](https://github.com/cloud-itonami/cloud-itonami-isic-6492),
[`6920`](https://github.com/cloud-itonami/cloud-itonami-isic-6920),
[`6611`](https://github.com/cloud-itonami/cloud-itonami-isic-6611),
[`7120`](https://github.com/cloud-itonami/cloud-itonami-isic-7120),
[`8620`](https://github.com/cloud-itonami/cloud-itonami-isic-8620),
[`8530`](https://github.com/cloud-itonami/cloud-itonami-isic-8530),
[`9200`](https://github.com/cloud-itonami/cloud-itonami-isic-9200),
[`7500`](https://github.com/cloud-itonami/cloud-itonami-isic-7500),
[`9603`](https://github.com/cloud-itonami/cloud-itonami-isic-9603),
[`9521`](https://github.com/cloud-itonami/cloud-itonami-isic-9521)) --
the first leisure-attractions vertical (ISIC division 93) in this
fleet. Here it is **ParkOps-LLM ⊣ Ride Safety Governor**.

> **Why an actor layer at all?** An LLM is great at drafting a ride-
> status summary, normalizing intake, and checking whether a ride's
> own certified-operator headcount actually satisfies its own minimum
> staffing requirement -- but it has **no notion of which
> jurisdiction's ride-safety requirements are official, no license to
> reopen a real ride to patrons, and no way to know on its own whether
> a ride's post-hold inspection actually passed**. Letting it reopen a
> ride directly invites fabricated jurisdiction citations, a ride
> reopened before its post-hold inspection passed, and a ride
> understaffed relative to its own minimum operator requirement being
> quietly waved through -- and liability, and patron safety risk, for
> whoever runs it. This project seals the ParkOps-LLM into a single
> node and wraps it with an independent **Ride Safety Governor**, a
> human **approval workflow**, and an immutable **audit ledger**.

## Scope: what this actor does and does not do

This actor covers ride intake through jurisdiction assessment, post-
hold inspection screening and ride reopening. It does **not**, by
itself, hold any license required to operate an amusement park in a
given jurisdiction, and it does not claim to. It also does **not**
model a full ride-safety engineering program -- no structural-fatigue
analysis, no ride-specific engineering inspection criteria, no
patron-load/capacity modeling (see `parksafety.registry/operators-
sufficient?`'s own docstring for the honest simplification this
makes: a single certified-operator-headcount-vs-minimum comparison,
not a full staffing/competency program). Whoever deploys and operates
a live instance (a licensed park operator) supplies the jurisdiction-
specific license, the real mechanical/safety-engineering expertise
and the real park-operations-system integrations, and bears that
jurisdiction's liability -- the software supplies the governed, spec-
cited, audited execution scaffold so that operator does not have to
build the compliance layer from scratch for every new market.

### Actuation

**Reopening a real ride to patrons after a safety hold is never
autonomous, at any phase, by construction.** Two independent layers
enforce this (`parksafety.governor`'s `:actuation/reopen-ride` high-
stakes gate and `parksafety.phase`'s phase table, which never puts
`:ride/reopen` in any phase's `:auto` set) -- see `parksafety.phase`'s
docstring and `test/parksafety/phase_test.clj`'s `ride-reopen-never-
auto-at-any-phase`. The actor may draft, check and recommend; a human
licensed ride operator/inspector is always the one who actually
reopens a ride. Like `6511`/`6621`/`6629`/`6612`/`6492`/`7120`/`8620`/
`7500`/`9603`, this actor has ONE actuation event.

## The core contract

```
ride intake + jurisdiction facts (parksafety.facts, spec-cited)
        |
        v
   ┌──────────────┐   proposal      ┌───────────────────────┐
   │ ParkOps-LLM  │ ─────────────▶ │ Ride Safety                 │  (independent system)
   │  (sealed)    │  + citations    │ Governor: spec-basis ·      │
   └──────────────┘                 │ evidence-incomplete ·        │
                             commit ◀────┼──────────▶ hold │ inspection-not-passed ·
                                 │             │           │ operators-insufficient
                           record + ledger  escalate ─▶ human   (min-threshold, two
                                             (ALWAYS for         fields on one entity) ·
                                              :ride/reopen)        already-reopened
```

**The ParkOps-LLM never reopens a ride the Ride Safety Governor would
reject, and never does so without a human sign-off.** Hard violations
(fabricated jurisdiction requirements; unsupported reopening evidence;
a failed post-hold inspection; a ride understaffed relative to its own
minimum operator requirement; a double reopening) force **hold** and
*cannot* be approved past; a clean reopening proposal still always
routes to a human.

## Run

```bash
clojure -M:dev:run     # walk one clean lifecycle (ride reopening) + four HARD-hold cases through the actor
clojure -M:dev:test    # governor contract · phase invariants · store parity · registry conformance · facts coverage
clojure -M:lint        # clj-kondo (errors fail; CI mirrors this)
```

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here a ride-inspection robot
performs physical mechanical safety checks, under the actor, gated by
the independent **Ride Safety Governor**. The governor never
dispatches hardware itself; `:high`/`:safety-critical` actions require
human sign-off.

## Open business

This repository is not only source code. It is a public, forkable
business model:

| Layer | What is open |
|---|---|
| OSS core | Actor runtime, Ride Safety Governor, ride-reopening draft records, audit ledger |
| Business blueprint | Customer, offer, pricing, unit economics, sales motion |
| Operator playbook | How to fork, license, deploy and support the service in a jurisdiction |
| Trust controls | Governance, security reporting, actuation invariant, audit requirements |

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md) to start this as an
open business on itonami.cloud, and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for the
full architecture and decision record.

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`9321`). Like `6920`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/`9521`,
this vertical's operational records are practice-specific rather than
a shared cross-operator data contract, so `parksafety.*` runs on the
generic identity/forms/dmn/bpmn/audit-ledger stack only -- no bespoke
domain capability lib to reference at all.

## Layout

| File | Role |
|---|---|
| `src/parksafety/store.cljc` | **Store** protocol -- `MemStore` ‖ `DatomicStore` (`langchain.db`) + append-only audit ledger + ride-reopening history. No dynamically-filed sub-record -- the actuation op acts directly on a pre-seeded ride, and the double-reopening guard checks a dedicated `:reopened?` boolean rather than a `:status` value |
| `src/parksafety/registry.cljc` | Ride-reopening draft records, plus `operators-sufficient?` -- reuses this fleet's MINIMUM-threshold pure-ground-truth-recompute shape, but the FIRST instance to compare two fields on the SAME entity rather than one field against a shared constant |
| `src/parksafety/facts.cljc` | Per-jurisdiction ride-safety catalog with an official spec-basis citation per entry, honest coverage reporting |
| `src/parksafety/parkopsllm.cljc` | **ParkOps-LLM Advisor** -- `mock-advisor` ‖ `llm-advisor`; intake/assessment/inspection-screening/ride-reopening proposals |
| `src/parksafety/governor.cljc` | **Ride Safety Governor** -- 4 HARD checks (spec-basis · evidence-incomplete · inspection-not-passed, unconditional evaluation · operators-insufficient, pure ground-truth two-field recompute) + already-reopened guard + 1 soft (confidence/actuation gate) |
| `src/parksafety/phase.cljc` | **Phase 0→3** -- read-only → assisted intake → assisted assess → supervised (reopening always human; ride intake is the ONLY auto-eligible op, no direct capital risk) |
| `src/parksafety/operation.cljc` | **OperationActor** -- langgraph-clj StateGraph |
| `src/parksafety/sim.cljc` | demo driver |
| `test/parksafety/*_test.clj` | governor contract · phase invariants · store parity · registry conformance · facts coverage |

## Business-process coverage (honest)

This actor covers ride intake through jurisdiction assessment, post-
hold inspection screening and ride reopening -- the core governed
lifecycle this blueprint's own `docs/business-model.md` names as its
Offer:

| Covered | Not covered (out of scope for this R0) |
|---|---|
| Ride intake + per-jurisdiction ride-safety checklisting, HARD-gated on an official spec-basis citation (`:ride/intake`/`:jurisdiction/assess`) | A full ride-safety engineering program (structural-fatigue analysis, ride-specific engineering inspection criteria, patron-load/capacity modeling -- see `operators-sufficient?`'s docstring) |
| Post-hold inspection screening, evaluated unconditionally so the screening op itself can HARD-hold on its own finding (`:inspection/screen`) | Real park-operations-system integration, ticketing/admission-capacity reporting |
| Ride reopening, HARD-gated on the ride's certified-operator headcount satisfying its own minimum staffing requirement and a double-reopening guard (`:ride/reopen`) | Ongoing ride-maintenance-scheduling workflows themselves |
| Immutable audit ledger for every intake/assessment/screening/reopening decision | |

Extending coverage is additive: add the next gate (e.g. a weather-
condition-hold check) as its own governed op with its own HARD checks
and tests, following the SAME "an independent governor re-verifies
against the actor's own records before any real-world act" pattern
this repo's flagship op already establishes.

## Jurisdiction coverage (honest)

`parksafety.facts/coverage` reports how many requested jurisdictions
actually have an official spec-basis in `parksafety.facts/catalog` --
currently 4 seeded (JPN, USA, GBR, DEU) out of ~194 jurisdictions
worldwide. This is a starting catalog to prove the governor contract
end-to-end, not a claim of global coverage. Adding a jurisdiction is
additive: one map entry in `parksafety.facts/catalog`, citing a real
official source -- never fabricate a jurisdiction's requirements to make
coverage look bigger.

## Maturity

`:implemented` -- `ParkOps-LLM` + `Ride Safety Governor` run as real,
tested code (see `Run` above), promoted from the originally-published
`:blueprint`-tier scaffold, modeled closely on the nineteen prior
actors' architecture. See `docs/adr/0001-architecture.md` for the
history and design -- including a real test/demo-design bug this
build's own test suite caught and fixed (see that ADR's Decision 5).

## License

Code and implementation templates are AGPL-3.0-or-later.
