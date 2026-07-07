(ns parksafety.governor
  "Ride Safety Governor -- the independent compliance layer that earns
  the ParkOps-LLM the right to commit. The LLM has no notion of
  jurisdictional ride-safety law, whether a ride's own post-hold
  inspection actually passed, whether a ride's own certified-operator
  headcount actually satisfies its own minimum staffing requirement,
  or when an act stops being a draft and becomes a real-world ride
  reopening, so this MUST be a separate system able to *reject* a
  proposal and fall back to HOLD -- the amusement-park analog of
  `cloud-itonami-isic-6512`'s CasualtyGovernor.

  Five checks, in priority order, ALL HARD violations: a human approver
  CANNOT override them (you don't get to approve your way past a
  fabricated jurisdiction spec-basis, incomplete reopening evidence, a
  ride whose post-hold inspection didn't pass, a ride with fewer
  certified operators on duty than its own minimum staffing
  requirement, or a double reopening of the same ride). The
  confidence/actuation gate is SOFT: it asks a human to look (low
  confidence / actuation), and the human may approve -- but see
  `parksafety.phase`: for `:stake :actuation/reopen-ride` (a real ride
  reopening) NO phase ever allows auto-commit either. Two independent
  layers agree that actuation is always a human call.

    1. Spec-basis                  -- did the jurisdiction proposal cite
                                       an OFFICIAL source (`parksafety.
                                       facts`), or invent one? Like
                                       `credit.governor`'s/`clinic.
                                       governor`'s/`veterinary.
                                       governor`'s/`funeral.governor`'s
                                       actuation ops, `:ride/reopen`
                                       acts directly on a pre-seeded
                                       ride (see `parksafety.store`'s
                                       own docstring) -- there is no
                                       'ride is missing' failure mode
                                       to guard against here.
    2. Evidence incomplete         -- for `:ride/reopen`, has the
                                       jurisdiction actually been
                                       assessed with a full reopening
                                       evidence checklist on file?
    3. Inspection not passed       -- reported by THIS proposal itself
                                       (an `:inspection/screen` that
                                       just found a failed inspection),
                                       or already on file for the ride
                                       (`:inspection/screen`/`:ride/
                                       reopen`). Evaluated
                                       UNCONDITIONALLY (not scoped to a
                                       specific op), the SAME discipline
                                       `casualty.governor/sanctions-
                                       violations`/`marketadmin.
                                       governor/surveillance-flag-
                                       unresolved-violations`/`testlab.
                                       governor/calibration-not-current-
                                       violations`/`clinic.governor/
                                       credential-not-current-
                                       violations`/`registrar.governor/
                                       integrity-flag-unresolved-
                                       violations`/`wagering.governor/
                                       patron-flag-unresolved-
                                       violations`/`veterinary.
                                       governor/credential-not-current-
                                       violations`/`funeral.governor/
                                       authorization-unverified-
                                       violations`/`repairshop.
                                       governor/safety-test-not-passed-
                                       violations` established -- the
                                       NINTH distinct application of
                                       this exact discipline.
    4. Operators insufficient      -- for `:ride/reopen`, INDEPENDENTLY
                                       recompute whether the ride's own
                                       `:certified-operators-on-duty`
                                       satisfies its own `:minimum-
                                       operators-required` (`parksafety.
                                       registry/operators-sufficient?`)
                                       -- needs no proposal inspection
                                       or stored-verdict lookup at all,
                                       reusing this fleet's MINIMUM-
                                       threshold pure-ground-truth-
                                       recompute shape (`marketadmin.
                                       governor`'s/`registrar.
                                       governor`'s shape) for a further
                                       domain, but the FIRST instance to
                                       compare two fields on the SAME
                                       entity rather than one field
                                       against a shared constant.
    5. Confidence floor / actuation
       gate                          -- LLM confidence below threshold,
                                       OR the op is `:ride/reopen` (a
                                       REAL safety-critical act) ->
                                       escalate.

  One more guard, double-reopening prevention, is enforced but NOT
  listed as a numbered HARD check above because it needs no upstream
  comparison at all -- `already-reopened-violations` refuses to
  reopen the SAME ride twice, off a dedicated `:reopened?` fact (never
  a `:status` value) -- the SAME 'check a dedicated boolean, not
  status' discipline `accounting.governor`'s/`marketadmin.
  governor`'s/`testlab.governor`'s/`clinic.governor`'s/`registrar.
  governor`'s/`wagering.governor`'s/`veterinary.governor`'s/`funeral.
  governor`'s/`repairshop.governor`'s guards establish, informed by
  `cloud-itonami-isic-6492`'s status-lifecycle bug (ADR-2607071320)."
  (:require [parksafety.facts :as facts]
            [parksafety.registry :as registry]
            [parksafety.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Reopening a real ride to patrons after a safety hold is the ONE
  real-world actuation event this actor performs -- a single-member
  set, matching `cloud-itonami-isic-6511`'s/`6621`'s/`6629`'s/`6612`'s/
  `6492`'s/`7120`'s/`8620`'s/`7500`'s/`9603`'s single-actuation shape."
  #{:actuation/reopen-ride})

;; ----------------------------- checks -----------------------------

(defn- spec-basis-violations
  "A `:jurisdiction/assess` (or `:ride/reopen`) proposal with no spec-
  basis citation is a HARD violation -- never invent a jurisdiction's
  ride-safety requirements."
  [{:keys [op]} proposal]
  (when (contains? #{:jurisdiction/assess :ride/reopen} op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式spec-basisの引用が無い提案は法域要件として扱えない"}]))))

(defn- evidence-incomplete-violations
  "For `:ride/reopen`, the jurisdiction's required inspection/
  maintenance-log/operator-certification/incident-report evidence must
  actually be satisfied -- do not trust the advisor's self-reported
  confidence alone."
  [{:keys [op subject]} st]
  (when (= op :ride/reopen)
    (let [r (store/ride st subject)
          assessment (store/assessment-of st subject)]
      (when-not (and assessment
                     (facts/required-evidence-satisfied?
                      (:jurisdiction r) (:checklist assessment)))
        [{:rule :evidence-incomplete
          :detail "法域の必要書類(定期検査報告書/整備記録/運転者資格確認記録等)が充足していない状態での再開提案"}]))))

(defn- inspection-not-passed-violations
  "A not-passed post-hold inspection -- reported by THIS proposal (e.g.
  an `:inspection/screen` that itself just found a failure), or
  already on file in the store for the ride (`:inspection/screen`/
  `:ride/reopen`) -- is a HARD, un-overridable hold. Evaluated
  UNCONDITIONALLY (not scoped to a specific op) so the screening op
  itself can HARD-hold on its own finding."
  [{:keys [op subject]} proposal st]
  (let [hit-in-proposal? (= :failed (get-in proposal [:value :verdict]))
        ride-id (when (contains? #{:inspection/screen :ride/reopen} op) subject)
        hit-on-file? (and ride-id (= :failed (:verdict (store/inspection-of st ride-id))))]
    (when (or hit-in-proposal? hit-on-file?)
      [{:rule :inspection-not-passed
        :detail "点検再開前検査に合格していない遊戯施設を再開する提案は進められない"}])))

(defn- operators-insufficient-violations
  "For `:ride/reopen`, INDEPENDENTLY recompute whether the ride's own
  certified-operators-on-duty satisfies its own minimum-operators-
  required via `parksafety.registry/operators-sufficient?` -- needs no
  proposal inspection or stored-verdict lookup at all, reusing this
  fleet's MINIMUM-threshold pure-ground-truth-recompute shape for a
  further domain."
  [{:keys [op subject]} st]
  (when (= op :ride/reopen)
    (let [r (store/ride st subject)]
      (when-not (registry/operators-sufficient? r)
        [{:rule :operators-insufficient
          :detail (str subject " の配置有資格運転者数(" (:certified-operators-on-duty r)
                      ")が最低必要人数(" (:minimum-operators-required r) ")を下回っている")}]))))

(defn- already-reopened-violations
  "For `:ride/reopen`, refuses to reopen the SAME ride twice, off a
  dedicated `:reopened?` fact (never a `:status` value) -- see ns
  docstring for why this sidesteps the status-lifecycle risk `cloud-
  itonami-isic-6492`'s ADR-0001 documents."
  [{:keys [op subject]} st]
  (when (= op :ride/reopen)
    (when (store/ride-already-reopened? st subject)
      [{:rule :already-reopened
        :detail (str subject " は既に再開済み")}])))

(defn check
  "Censors a ParkOps-LLM proposal against the governor rules. Returns
   {:ok? bool :violations [..] :confidence c :escalate? bool :high-stakes? bool
    :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (spec-basis-violations request proposal)
                           (evidence-incomplete-violations request st)
                           (inspection-not-passed-violations request proposal st)
                           (operators-insufficient-violations request st)
                           (already-reopened-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
