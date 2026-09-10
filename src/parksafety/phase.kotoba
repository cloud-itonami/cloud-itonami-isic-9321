(ns parksafety.phase
  "Phase 0->3 staged rollout -- the amusement-park analog of
  `cloud-itonami-isic-6512`'s `casualty.phase`.

    Phase 0  read-only        -- no writes, still governor-gated.
    Phase 1  assisted-intake  -- ride intake allowed, every write
                                 needs human approval.
    Phase 2  assisted-assess  -- adds jurisdiction assessment +
                                 inspection screening writes, still
                                 approval.
    Phase 3  supervised auto  -- governor-clean, high-confidence
                                 `:ride/intake` (no capital risk yet)
                                 may auto-commit. `:ride/reopen` NEVER
                                 auto-commits, at any phase.

  `:ride/reopen` is deliberately ABSENT from every phase's `:auto`
  set, including phase 3 -- a permanent structural fact, not a rollout
  milestone still to come. Reopening a real ride to patrons after a
  safety hold is the ONE real-world, safety-critical legal act this
  actor performs; it is always a human licensed ride operator/
  inspector's call. `parksafety.governor`'s `:actuation/reopen-ride`
  high-stakes gate enforces the same invariant independently -- two
  layers, not one, agree on this. `:inspection/screen` is likewise
  never auto-eligible, at any phase -- the same posture every
  sibling's KYC/conflict/independence/surveillance/calibration/
  credential/integrity/patron/authorization/safety screening op has.
  Like `credit.phase`/`accounting.phase`/`marketadmin.phase`/`testlab.
  phase`/`clinic.phase`/`registrar.phase`/`wagering.phase`/
  `veterinary.phase`/`funeral.phase`/`repairshop.phase`, phase 3's
  `:auto` set here has only ONE member (`:ride/intake`) -- this domain
  has no separate no-capital-risk 'file' lifecycle distinct from the
  ride itself.")

(def read-ops  #{})
(def write-ops #{:ride/intake :jurisdiction/assess :inspection/screen
                 :ride/reopen})

;; NOTE the invariant: `:ride/reopen` is a member of `write-ops`
;; (governor-gated like any write) but is NEVER a member of any
;; phase's `:auto` set below. Do not add it there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}."
  {0 {:label "read-only"       :writes #{}                                                          :auto #{}}
   1 {:label "assisted-intake" :writes #{:ride/intake}                                              :auto #{}}
   2 {:label "assisted-assess" :writes #{:ride/intake :jurisdiction/assess :inspection/screen}       :auto #{}}
   3 {:label "supervised-auto" :writes write-ops
      :auto #{:ride/intake}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:ride/reopen` is never auto-eligible at any phase, so it always
    escalates once the governor clears it (or holds if the governor
    doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a Ride Safety Governor verdict to a base disposition before the
  phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
