(ns parksafety.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300):
  this repo previously had NO demo page and no generator at all. This
  namespace drives the REAL actor stack (`parksafety.operation` ->
  `parksafety.governor` -> `parksafety.store`) through a scenario
  adapted from this repo's own `parksafety.sim` demo driver
  (`clojure -M:dev:run`, confirmed BEFORE writing this file to use ride
  ids that DO match `parksafety.store/demo-data`), extended by one case
  so that ALL FIVE of the governor's HARD rules fire in a single run,
  and rendered deterministically -- no invented numbers, no timestamps
  in the page content, byte-identical across reruns against the same
  seed (verify by diffing two consecutive runs).

  Nothing on the page is hand-typed domain data. Ride rows come from
  `store/all-rides`, the committed registers from `store/assessment-of`
  / `store/inspection-of` / `store/reopening-history`, the HARD-hold
  rows from the governor's own `:violations` (rule AND detail string),
  the jurisdiction table from `parksafety.facts/catalog`, and the
  coverage line from `parksafety.facts/coverage` called with the
  jurisdictions the seeded rides actually carry.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [parksafety.facts :as facts]
            [parksafety.governor :as governor]
            [parksafety.phase :as phase]
            [parksafety.registry :as registry]
            [parksafety.store :as store]
            [parksafety.operation :as op]
            [langgraph.graph :as g]))

(def ^:private operator
  "The human in the loop. Same shape `parksafety.sim` injects."
  {:actor-id "op-1" :actor-role :licensed-ride-operator :phase 3})

;; ----------------------------- driving the real actor -----------------------------

(defn- run-demo!
  "Runs a fresh seeded store through a scenario that reaches every
  disposition this actor can produce.

  ride-1 clears the full lifecycle -- intake (auto-commits: phase 3
  lists `:ride/intake` in its `:auto` set and the proposal is clean), a
  jurisdiction assessment (phase-gated, approved), a post-hold
  inspection screening (approved), and finally a ride reopening, which
  ALWAYS escalates: `:actuation/reopen-ride` is high-stakes to the
  governor AND absent from every phase's `:auto` set, so two
  independent layers agree a human must call it.

  Then all five HARD rules fire, each isolated to exactly one subject
  so the console can attribute a rule to a row:

    :no-spec-basis          ride-2, jurisdiction/assess -- ATL is not
                            in `parksafety.facts/catalog`
    :inspection-not-passed  ride-3, inspection/screen -- the screening
                            finds its OWN failure and holds on it
    :operators-insufficient ride-4, ride/reopen -- 1 certified operator
                            on duty against its own minimum of 3
    :already-reopened       ride-1, ride/reopen a second time
    :evidence-incomplete    ride-2, ride/reopen -- no assessment ever
                            committed for it (its assess HARD-held), so
                            the jurisdiction evidence checklist is not
                            on file

  None of the five ever reaches a human: a HARD violation holds before
  the approval node.

  Returns {:db store :audit [..]}. The `:audit` vector is needed
  because `:approval-granted` facts live in the graph's `:audit`
  channel, NOT in the store ledger -- see `approver-cell` below, which
  is exactly why the approver disclosure has to join the two."
  []
  (let [db (store/seed-db)
        actor (op/build db)
        ;; thread-id -> latest :audit vector. langgraph's :audit channel
        ;; uses `:reducer into`, so a thread's LAST run already carries
        ;; that thread's complete audit; keeping only the last avoids
        ;; double-counting a resumed thread.
        audits (atom [])
        ;; NOTE `g/run*` returns {:state :events :status :frontier} -- the
        ;; :audit channel is under :state, NOT at the top level. Reading
        ;; it from the top level yields nil, which would make every
        ;; approver render as "auto-committed (no approver)": a silent
        ;; measurement failure that is indistinguishable from a real
        ;; finding. `-main` asserts a non-empty grant set to keep that
        ;; from ever passing quietly again.
        remember! (fn [tid result]
                    (swap! audits (fn [v]
                                    (conj (vec (remove #(= tid (first %)) v))
                                          [tid (vec (:audit (:state result)))])))
                    result)
        exec! (fn [tid request]
                (remember! tid (g/run* actor {:request request :context operator}
                                       {:thread-id tid})))
        approve! (fn [tid]
                   (remember! tid (g/run* actor {:approval {:status :approved :by "op-1"}}
                                          {:thread-id tid :resume? true})))]
    ;; --- ride-1: the full clean lifecycle -------------------------------
    (exec! "r1-intake" {:op :ride/intake :subject "ride-1"
                        :patch {:id "ride-1" :ride-name "Sakura Coaster"}})

    (exec! "r1-assess" {:op :jurisdiction/assess :subject "ride-1"})
    (approve! "r1-assess")

    (exec! "r1-screen" {:op :inspection/screen :subject "ride-1"})
    (approve! "r1-screen")

    (exec! "r1-reopen" {:op :ride/reopen :subject "ride-1"})
    (approve! "r1-reopen")

    ;; --- ride-4: assessed + screened clean, then held on staffing -------
    (exec! "r4-assess" {:op :jurisdiction/assess :subject "ride-4"})
    (approve! "r4-assess")

    (exec! "r4-screen" {:op :inspection/screen :subject "ride-4"})
    (approve! "r4-screen")

    ;; --- the five HARD holds -------------------------------------------
    (exec! "r2-assess" {:op :jurisdiction/assess :subject "ride-2" :no-spec? true})
    (exec! "r3-screen" {:op :inspection/screen :subject "ride-3"})
    (exec! "r4-reopen" {:op :ride/reopen :subject "ride-4"})
    (exec! "r1-reopen-again" {:op :ride/reopen :subject "ride-1"})
    (exec! "r2-reopen" {:op :ride/reopen :subject "ride-2"})

    {:db db :audit (vec (mapcat second @audits))}))

;; ----------------------------- html helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- kw->s [v]
  (cond (keyword? v) (name v)
        (nil? v) ""
        :else (str v)))

(defn- basis->s [basis]
  (str/join ", " (map kw->s basis)))

(defn- tag [class text]
  (format "<span class=\"%s\">%s</span>" class text))

;; ----------------------------- derived cells -----------------------------

(defn- facts-for-subject [ledger subject]
  (filter #(= subject (:subject %)) ledger))

(defn- status-cell
  "The disposition of the LAST ledger fact recorded for this ride."
  [ledger ride-id]
  (let [f (last (facts-for-subject ledger ride-id))]
    (cond
      (nil? f) (tag "muted" "no activity")
      (= :governor-hold (:t f))
      (tag "critical" (str "HARD hold &middot; "
                           (esc (basis->s (:basis f)))))
      (= :approval-rejected (:t f)) (tag "warn" "approval rejected")
      (= :committed (:t f)) (tag "ok" (str "committed &middot; "
                                           (esc (kw->s (:op f)))))
      :else (tag "muted" (esc (kw->s (:t f)))))))

(defn- reopening-cell [{:keys [reopened? reopening-number]}]
  (if reopened?
    (tag "ok" (str "reopened &middot; <code>" (esc reopening-number) "</code>"))
    (tag "muted" "still held")))

(defn- staffing-cell [{:keys [certified-operators-on-duty minimum-operators-required] :as r}]
  (let [txt (str certified-operators-on-duty " / " minimum-operators-required " min")]
    (if (registry/operators-sufficient? r)
      (tag "ok" (esc txt))
      (tag "critical" (esc txt)))))

(defn- inspection-cell [{:keys [post-hold-inspection-passed?]}]
  (if post-hold-inspection-passed?
    (tag "ok" "passed")
    (tag "critical" "NOT passed")))

(defn- jurisdiction-cell [{:keys [jurisdiction]}]
  (if (facts/spec-basis jurisdiction)
    (format "<code>%s</code> %s" (esc jurisdiction) (tag "ok" "spec-basis on file"))
    (format "<code>%s</code> %s" (esc jurisdiction) (tag "critical" "no spec-basis"))))

(defn- ride-row [ledger {:keys [id ride-name hold-reason] :as r}]
  (format (str "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td>"
               "<td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>")
          (esc id) (esc ride-name) (jurisdiction-cell r) (esc hold-reason)
          (inspection-cell r) (staffing-cell r)
          (reopening-cell r) (status-cell ledger id)))

;; ----------------------------- approver attribution -----------------------------
;;
;; Measured at render time rather than asserted. `parksafety.operation`
;; builds every commit record with BOTH `:value` (the raw proposal
;; value) and `:payload` (the same value plus `:approved-by` once a
;; human approves). Whether the approver survives into the SSoT
;; therefore depends on which of the two each `commit-record!` branch
;; in `parksafety.store` happens to read -- and the branches differ.
;; Rather than hardcode today's answer, the two functions below WALK
;; the committed register entry and check whether the approver key is
;; actually present, falling back to the audit fact and saying so. If
;; a store branch is later fixed to read `:payload`, this page starts
;; reporting `retained in record` on its own, with no edit here.

(defn- approval-by
  "The approver recorded in the graph's :audit channel for [op subject]."
  [audit op subject]
  (some (fn [f]
          (when (and (= :approval-granted (:t f))
                     (= op (:op f))
                     (= subject (:subject f)))
            (:by f)))
        audit))

(defn- approver-cell
  "Renders the approver for one committed register entry, distinguishing
  `nobody approved this` from `the store did not keep the approver`.
  `record-key` is looked up in the committed record itself."
  [record record-key audit op subject]
  (let [in-record (get record record-key)
        in-audit  (approval-by audit op subject)]
    (cond
      in-record (tag "ok" (str (esc in-record) " &middot; retained in record"))
      in-audit  (tag "warn" (str (esc in-audit)
                                 " &middot; audit only &mdash; not retained in record"))
      :else     (tag "muted" "auto-committed (no approver)"))))

(defn- register-rows
  "One row per committed register entry, across all three registers."
  [db audit]
  (let [rides (store/all-rides db)
        row (fn [register subject detail cell]
              (format (str "        <tr><td>%s</td><td><code>%s</code></td>"
                           "<td>%s</td><td>%s</td></tr>")
                      register (esc subject) detail cell))]
    (concat
     ;; jurisdiction assessments -- :assessment/set commits `:payload`
     (for [r rides
           :let [a (store/assessment-of db (:id r))]
           :when a]
       (row "jurisdiction assessment" (:id r)
            (format "%s &middot; %s evidence items &middot; <code>%s</code>"
                    (esc (:jurisdiction a))
                    (count (:checklist a))
                    (esc (:spec-basis a)))
            (approver-cell a :approved-by audit :jurisdiction/assess (:id r))))
     ;; post-hold inspection screenings -- :inspection/set commits `:payload`
     (for [r rides
           :let [i (store/inspection-of db (:id r))]
           :when i]
       (row "post-hold inspection" (:id r)
            (let [v (:verdict i)]
              (tag (if (= :passed v) "ok" "critical") (esc (kw->s v))))
            (approver-cell i :approved-by audit :inspection/screen (:id r))))
     ;; ride reopenings -- :ride/mark-reopened rebuilds the record from
     ;; `parksafety.registry` and reads neither `:value` nor `:payload`
     (for [rec (store/reopening-history db)]
       (row "ride reopening" (get rec "ride_id")
            (format "<code>%s</code> &middot; %s &middot; jurisdiction <code>%s</code>"
                    (esc (get rec "record_id"))
                    (esc (get rec "kind"))
                    (esc (get rec "jurisdiction")))
            (approver-cell rec "approved_by" audit :ride/reopen (get rec "ride_id")))))))

;; ----------------------------- governor rule table -----------------------------

(def ^:private hard-rules
  "The governor's five HARD rules, in the priority order
  `parksafety.governor/check` concatenates them. The rule keywords and
  the ordering are this actor's fixed contract; whether each one FIRED,
  on which subject, and with what detail string is read back out of the
  run below."
  [[:no-spec-basis          "jurisdiction/assess, ride/reopen"
    "Did the proposal cite an OFFICIAL spec-basis from parksafety.facts, or invent one?"]
   [:evidence-incomplete    "ride/reopen"
    "Is the jurisdiction's full required-evidence checklist actually on file for this ride?"]
   [:inspection-not-passed  "any op"
    "A failed post-hold inspection -- reported by this proposal itself, or already on file."]
   [:operators-insufficient "ride/reopen"
    "Independently recompute the ride's own certified operators on duty against its own minimum."]
   [:already-reopened       "ride/reopen"
    "Refuses a second reopening of the same ride, off a dedicated :reopened? fact."]])

(defn- hold-index
  "rule -> [{:subject .. :op .. :detail ..} ..], read out of the ledger."
  [ledger]
  (reduce (fn [acc f]
            (reduce (fn [a v]
                      (update a (:rule v) (fnil conj [])
                              {:subject (:subject f) :op (:op f) :detail (:detail v)}))
                    acc
                    (:violations f)))
          {}
          (filter #(= :governor-hold (:t %)) ledger)))

(defn- rule-row [idx [rule scope guards]]
  (let [hits (get idx rule)]
    (format (str "        <tr><td><code>:%s</code></td><td><code>%s</code></td><td>%s</td>"
                 "<td>%s</td><td>%s</td></tr>")
            (esc (name rule)) (esc scope) (esc guards)
            (if (seq hits)
              (tag "critical" (str "HARD hold &times; " (count hits) " &middot; "
                                   (esc (str/join ", " (map :subject hits)))))
              (tag "muted" "not exercised in this run"))
            (esc (or (:detail (first hits)) "")))))

;; ----------------------------- phase / action gate -----------------------------

(defn- phase-rows
  "Derived directly from `parksafety.phase/phases` -- the rollout table
  itself, not a description of it."
  []
  (for [[n {:keys [label writes auto]}] (sort-by key phase/phases)]
    (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
            n (esc label)
            (if (seq writes)
              (str/join " " (map #(str "<code>" (esc %) "</code>") (sort (map str writes))))
              (tag "muted" "none"))
            (if (seq auto)
              (str/join " " (map #(str "<code>" (esc %) "</code>") (sort (map str auto))))
              (tag "muted" "none &mdash; every write needs a human")))))

(defn- op-gate-rows
  "One row per op in `phase/write-ops`, with its phase-3 posture and
  governor posture derived from `parksafety.phase` and
  `parksafety.governor/high-stakes`."
  []
  (let [{:keys [writes auto]} (get phase/phases phase/default-phase)]
    (for [o (sort-by str phase/write-ops)]
      (format "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
              (esc (str o))
              (cond
                (not (contains? writes o)) (tag "critical" "blocked at phase 3")
                (contains? auto o) (tag "ok" "auto-commit when governor-clean")
                :else (tag "warn" "human approval required"))
              (if (contains? governor/high-stakes :actuation/reopen-ride)
                (if (= o :ride/reopen)
                  (tag "warn" "high-stakes &middot; ALWAYS escalates &middot; never in any phase's :auto set")
                  (tag "muted" "not high-stakes"))
                (tag "muted" "not high-stakes"))))))

;; ----------------------------- jurisdiction catalog -----------------------------

(defn- jurisdiction-rows []
  (for [[iso3 {:keys [name owner-authority legal-basis required-evidence provenance]}]
        (sort-by key facts/catalog)]
    (format (str "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td>"
                 "<td>%s</td><td><code>%s</code></td></tr>")
            (esc iso3) (esc name) (esc owner-authority) (esc legal-basis)
            (count required-evidence) (esc provenance))))

;; ----------------------------- ledger -----------------------------

(defn- ledger-row [{:keys [t op subject disposition basis confidence]}]
  (format (str "        <tr><td>%s</td><td><code>%s</code></td><td><code>%s</code></td>"
               "<td>%s</td><td>%s</td><td>%s</td></tr>")
          (case t
            :committed (tag "ok" "committed")
            :governor-hold (tag "critical" "governor-hold")
            :approval-rejected (tag "warn" "approval-rejected")
            (esc (kw->s t)))
          (esc (kw->s op)) (esc subject)
          (esc (kw->s disposition))
          (esc (basis->s basis))
          (if (nil? confidence) "" (esc confidence))))

;; ----------------------------- document -----------------------------

(defn render
  "Renders the operator console from a store `db` and the `audit`
  vector that `run-demo!` collected from the same run."
  [db audit]
  (let [ledger (vec (store/ledger db))
        rides (store/all-rides db)
        idx (hold-index ledger)
        holds (filter #(= :governor-hold (:t %)) ledger)
        commits (filter #(= :committed (:t %)) ledger)
        cov (facts/coverage (distinct (map :jurisdiction rides)))]
    (str
     "<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">"
     "<title>cloud-itonami-isic-9321 &middot; parksafety &middot; Operator Console</title><style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Amusement parks and theme parks (ISIC 9321) &mdash; Ride Safety Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample &middot; governor-gated &middot; ride reopening is ALWAYS a human call</span>\n"
     "</header>\n"
     "<main class=\"container\">\n"

     ;; -- run summary --
     "  <section class=\"card\">\n"
     "    <h2>This run</h2>\n"
     "    <p class=\"muted\">Build-time-generated from the real actor by <code>parksafety.render-html</code>"
     " (<code>clojure -M:dev:render-html</code>): <code>parksafety.operation</code> &rarr;"
     " <code>parksafety.governor</code> &rarr; <code>parksafety.store</code>, driven through"
     " <code>langgraph.graph/run*</code>. Every identifier below is seeded in"
     " <code>parksafety.store/demo-data</code> or produced by this run &mdash; nothing is hand-typed.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Measure</th><th>Value</th></tr></thead>\n"
     "      <tbody>\n"
     (format "        <tr><td>Rides in the SSoT</td><td>%s</td></tr>\n" (count rides))
     (format "        <tr><td>Audit ledger facts</td><td>%s</td></tr>\n" (count ledger))
     (format "        <tr><td>Committed operations</td><td>%s</td></tr>\n" (count commits))
     (format "        <tr><td>HARD governor holds</td><td>%s</td></tr>\n"
             (tag "critical" (str (count holds) " &middot; "
                                  (count (keys idx)) " distinct rules fired")))
     (format "        <tr><td>Ride reopenings committed</td><td>%s</td></tr>\n"
             (count (store/reopening-history db)))
     (format "        <tr><td>Rollout phase</td><td>%s (%s)</td></tr>\n"
             (:phase operator)
             (esc (:label (get phase/phases (:phase operator)))))
     (format "        <tr><td>Governor confidence floor</td><td>%s</td></tr>\n"
             governor/confidence-floor)
     (format "        <tr><td>Jurisdiction spec-basis coverage</td><td>%s of %s requested &middot; covered %s &middot; missing %s</td></tr>\n"
             (:covered cov) (:requested cov)
             (esc (str/join ", " (:covered-jurisdictions cov)))
             (if (seq (:missing-jurisdictions cov))
               (tag "critical" (esc (str/join ", " (:missing-jurisdictions cov))))
               (tag "ok" "none")))
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     ;; -- rides --
     "  <section class=\"card\">\n"
     "    <h2>Rides under safety hold</h2>\n"
     "    <p class=\"muted\">The SSoT after this run. Staffing is shown as certified operators on duty against"
     " that ride's OWN minimum &mdash; a per-ride ground-truth field, independently recomputed by the"
     " governor via <code>parksafety.registry/operators-sufficient?</code>, never trusted from the proposal.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Ride</th><th>Name</th><th>Jurisdiction</th><th>Hold reason</th>"
     "<th>Post-hold inspection</th><th>Certified operators</th><th>Reopening</th><th>Last ledger fact</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map (partial ride-row ledger) rides)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     ;; -- HARD rules --
     "  <section class=\"card\">\n"
     "    <h2>Ride Safety Governor &mdash; HARD rules</h2>\n"
     "    <p class=\"muted\">All five are HARD: a human approver CANNOT override them, and a proposal that"
     " trips one never reaches the approval node at all. The detail column is the governor's own"
     " <code>:detail</code> string from this run, not a paraphrase.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Rule</th><th>Scope</th><th>What it guards</th><th>This run</th><th>Governor detail</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map (partial rule-row idx) hard-rules)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     ;; -- action gate --
     "  <section class=\"card\">\n"
     "    <h2>Action gate at phase 3</h2>\n"
     "    <p class=\"muted\">Two independent layers agree that reopening a ride is a human act: the governor"
     " marks <code>:actuation/reopen-ride</code> high-stakes, and <code>parksafety.phase</code> omits"
     " <code>:ride/reopen</code> from every phase's <code>:auto</code> set &mdash; a permanent structural"
     " fact, not a rollout milestone still to come.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Phase-3 gate</th><th>Governor stake</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (op-gate-rows)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     ;; -- rollout phases --
     "  <section class=\"card\">\n"
     "    <h2>Rollout phases</h2>\n"
     "    <p class=\"muted\">Read straight out of <code>parksafety.phase/phases</code>.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Phase</th><th>Label</th><th>Writes allowed</th><th>Auto-commit allowed</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (phase-rows)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     ;; -- committed registers + approver attribution --
     "  <section class=\"card\">\n"
     "    <h2>Committed registers &amp; approver attribution</h2>\n"
     "    <p class=\"muted\">Derived at render time by reading each committed record back out of the store and"
     " checking whether the approver key is actually present. <strong>audit only &mdash; not retained in"
     " record</strong> means a human DID approve (the <code>:approval-granted</code> audit fact names them)"
     " but this store branch rebuilt or committed the record without carrying the approver into the SSoT."
     " Omitting the approver silently would make &ldquo;nobody approved&rdquo; indistinguishable from"
     " &ldquo;the store dropped it&rdquo;, so the join is stated explicitly. If a store branch is later"
     " changed to persist the approver, this column reports it without any edit to the renderer.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Register</th><th>Ride</th><th>Committed record</th><th>Approver</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (register-rows db audit)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     ;; -- jurisdiction catalog --
     "  <section class=\"card\">\n"
     "    <h2>Jurisdiction spec-basis catalog</h2>\n"
     "    <p class=\"muted\">"
     (esc (:note cov))
     "</p>\n"
     "    <table>\n"
     "      <thead><tr><th>ISO3</th><th>Jurisdiction</th><th>Owner authority</th><th>Legal basis</th>"
     "<th>Required evidence</th><th>Provenance</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (jurisdiction-rows)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     ;; -- ledger --
     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log &mdash; every commit and every hold this scenario"
     " produced, in order. A HARD hold writes the rejection to the ledger and mutates nothing.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Ride</th><th>Disposition</th><th>Basis</th><th>Confidence</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map ledger-row ledger)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "</main>\n"
     "<footer class=\"container\">\n"
     "  <p class=\"muted\">cloud-itonami-isic-9321 &middot; generated by <code>clojure -M:dev:render-html</code>"
     " &middot; deterministic: no timestamps, byte-identical across reruns against the same seed.</p>\n"
     "</footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db audit]} (run-demo!)
        ledger (store/ledger db)
        holds (filter #(= :governor-hold (:t %)) ledger)
        rules (distinct (mapcat #(map :rule (:violations %)) holds))
        grants (filter #(= :approval-granted (:t %)) audit)]
    ;; Build-time invariant, not a convention: a console that shows this
    ;; actor never being stopped would misrepresent it as ungated. If the
    ;; scenario stops producing HARD holds, fail the build rather than
    ;; publish a page that quietly lost its whole point.
    (when (zero? (count holds))
      (throw (ex-info (str "render-html: the scenario produced 0 :governor-hold records. "
                           "The operator console MUST demonstrate at least one HARD, "
                           "un-overridable governor hold; refusing to write a page that "
                           "would present this actor as ungated.")
                      {:ledger-facts (count ledger)
                       :governor-holds 0
                       :committed (count (filter #(= :committed (:t %)) ledger))})))
    ;; Evidence floor for the approver disclosure. This scenario approves
    ;; five operations by hand, so an EMPTY grant set never means "nobody
    ;; approved" -- it means the audit channel was not read correctly (it
    ;; lives under `:state`, and reading the top level silently yields
    ;; nil). Without this floor that failure renders as a plausible-looking
    ;; "auto-committed (no approver)" on every row.
    (when (zero? (count grants))
      (throw (ex-info (str "render-html: collected 0 :approval-granted audit facts, but this "
                           "scenario approves operations by hand. The audit channel was not "
                           "read correctly; refusing to publish an approver column that cannot "
                           "distinguish 'nobody approved' from 'not measured'.")
                      {:audit-facts (count audit)
                       :approval-granted 0})))
    (spit out (render db audit))
    (println "wrote" out
             (str "(" (count ledger) " ledger facts, "
                  (count holds) " HARD governor holds across "
                  (count rules) " distinct rules "
                  (pr-str (vec (sort-by name rules))) ", "
                  (count grants) " human approvals, "
                  (count (store/reopening-history db)) " ride reopenings)"))))
