(ns parksafety.store
  "SSoT for the amusement-park/ride-safety actor, behind a `Store`
  protocol so the backend is a swap, not a rewrite -- the same seam
  every prior `cloud-itonami-isic-*` actor in this fleet uses:

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/parksafety/store_contract_test.clj), which is the whole point:
  the actor, the Ride Safety Governor and the audit ledger never know
  which SSoT they run on.

  Like `clinic.store`'s/`veterinary.store`'s/`funeral.store`'s simpler
  entities, a RIDE is acted on directly by the ONE actuation op -- no
  dynamically-filed sub-record, and the double-reopening guard checks
  a dedicated `:reopened?` boolean rather than a `:status` value, the
  same discipline `accounting.governor`'s/`marketadmin.governor`'s/
  `testlab.governor`'s/`clinic.governor`'s/`registrar.governor`'s/
  `wagering.governor`'s/`veterinary.governor`'s/`funeral.governor`'s/
  `repairshop.governor`'s guards establish.

  The ledger stays append-only on every backend: 'which ride was
  screened for a passed post-hold inspection, which ride was reopened,
  on what jurisdictional basis, approved by whom' is always a query
  over an immutable log -- the audit trail a patron trusting a park
  needs, and the evidence an operator needs if a reopening is later
  disputed."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [parksafety.registry :as registry]
            [langchain.db :as d]))

(defprotocol Store
  (ride [s id])
  (all-rides [s])
  (inspection-of [s ride-id] "committed post-hold inspection screening verdict for a ride, or nil")
  (assessment-of [s ride-id] "committed jurisdiction assessment, or nil")
  (ledger [s])
  (reopening-history [s] "the append-only ride-reopening history (parksafety.registry drafts)")
  (next-sequence [s jurisdiction] "next ride-reopening-number sequence for a jurisdiction")
  (ride-already-reopened? [s ride-id] "has this ride already been reopened?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-rides [s rides] "replace/seed the ride directory (map id->ride)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained ride set so the actor + tests run offline."
  []
  {:rides
   {"ride-1" {:id "ride-1" :ride-name "Sakura Coaster" :hold-reason "scheduled post-incident inspection"
               :post-hold-inspection-passed? true :certified-operators-on-duty 3
               :minimum-operators-required 2 :reopened? false :jurisdiction "JPN" :status :intake}
    "ride-2" {:id "ride-2" :ride-name "Atlantis Carousel" :hold-reason "scheduled maintenance"
               :post-hold-inspection-passed? true :certified-operators-on-duty 1
               :minimum-operators-required 1 :reopened? false :jurisdiction "ATL" :status :intake}
    "ride-3" {:id "ride-3" :ride-name "鈴木観覧車" :hold-reason "brake-system inspection"
               :post-hold-inspection-passed? false :certified-operators-on-duty 2
               :minimum-operators-required 2 :reopened? false :jurisdiction "JPN" :status :intake}
    "ride-4" {:id "ride-4" :ride-name "田中絶叫マシン" :hold-reason "restraint-system inspection"
               :post-hold-inspection-passed? true :certified-operators-on-duty 1
               :minimum-operators-required 3 :reopened? false :jurisdiction "JPN" :status :intake}}})

;; ----------------------------- shared commit logic -----------------------------

(defn- reopen-ride!
  "Backend-agnostic `:ride/mark-reopened` -- looks up the ride via the
  protocol and drafts the ride-reopening record, and returns {:result
  .. :ride-patch ..} for the caller to persist."
  [s ride-id]
  (let [r (ride s ride-id)
        seq-n (next-sequence s (:jurisdiction r))
        result (registry/register-ride-reopening ride-id (:jurisdiction r) seq-n)]
    {:result result
     :ride-patch {:reopened? true
                  :reopening-number (get result "reopening_number")}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (ride [_ id] (get-in @a [:rides id]))
  (all-rides [_] (sort-by :id (vals (:rides @a))))
  (inspection-of [_ id] (get-in @a [:inspections id]))
  (assessment-of [_ ride-id] (get-in @a [:assessments ride-id]))
  (ledger [_] (:ledger @a))
  (reopening-history [_] (:reopenings @a))
  (next-sequence [_ jurisdiction] (get-in @a [:sequences jurisdiction] 0))
  (ride-already-reopened? [_ ride-id] (boolean (get-in @a [:rides ride-id :reopened?])))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :ride/upsert
      (swap! a update-in [:rides (:id value)] merge value)

      :assessment/set
      (swap! a assoc-in [:assessments (first path)] payload)

      :inspection/set
      (swap! a assoc-in [:inspections (first path)] payload)

      :ride/mark-reopened
      (let [ride-id (first path)
            {:keys [result ride-patch]} (reopen-ride! s ride-id)
            jurisdiction (:jurisdiction (ride s ride-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:sequences jurisdiction] (fnil inc 0))
                       (update-in [:rides ride-id] merge ride-patch)
                       (update :reopenings registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-rides [s rides] (when (seq rides) (swap! a assoc :rides rides)) s))

(defn seed-db
  "A MemStore seeded with the demo ride set. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :assessments {} :inspections {} :ledger [] :sequences {}
                           :reopenings []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (assessment/inspection payloads, ledger facts,
  reopening records) are stored as EDN strings so `langchain.db`
  doesn't expand them into sub-entities -- the same convention every
  sibling actor's store uses."
  {:ride/id                     {:db/unique :db.unique/identity}
   :assessment/ride-id           {:db/unique :db.unique/identity}
   :inspection/ride-id            {:db/unique :db.unique/identity}
   :ledger/seq                     {:db/unique :db.unique/identity}
   :reopening/seq                   {:db/unique :db.unique/identity}
   :sequence/jurisdiction             {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- ride->tx [{:keys [id ride-name hold-reason post-hold-inspection-passed? certified-operators-on-duty
                        minimum-operators-required reopened? jurisdiction status reopening-number]}]
  (cond-> {:ride/id id}
    ride-name                       (assoc :ride/ride-name ride-name)
    hold-reason                       (assoc :ride/hold-reason hold-reason)
    (some? post-hold-inspection-passed?) (assoc :ride/post-hold-inspection-passed? post-hold-inspection-passed?)
    certified-operators-on-duty            (assoc :ride/certified-operators-on-duty certified-operators-on-duty)
    minimum-operators-required               (assoc :ride/minimum-operators-required minimum-operators-required)
    (some? reopened?)                          (assoc :ride/reopened? reopened?)
    jurisdiction                                 (assoc :ride/jurisdiction jurisdiction)
    status                                         (assoc :ride/status status)
    reopening-number                                (assoc :ride/reopening-number reopening-number)))

(def ^:private ride-pull
  [:ride/id :ride/ride-name :ride/hold-reason :ride/post-hold-inspection-passed?
   :ride/certified-operators-on-duty :ride/minimum-operators-required :ride/reopened?
   :ride/jurisdiction :ride/status :ride/reopening-number])

(defn- pull->ride [m]
  (when (:ride/id m)
    {:id (:ride/id m) :ride-name (:ride/ride-name m) :hold-reason (:ride/hold-reason m)
     :post-hold-inspection-passed? (boolean (:ride/post-hold-inspection-passed? m))
     :certified-operators-on-duty (:ride/certified-operators-on-duty m)
     :minimum-operators-required (:ride/minimum-operators-required m)
     :reopened? (boolean (:ride/reopened? m))
     :jurisdiction (:ride/jurisdiction m) :status (:ride/status m)
     :reopening-number (:ride/reopening-number m)}))

(defrecord DatomicStore [conn]
  Store
  (ride [_ id]
    (pull->ride (d/pull (d/db conn) ride-pull [:ride/id id])))
  (all-rides [_]
    (->> (d/q '[:find [?id ...] :where [?e :ride/id ?id]] (d/db conn))
         (map #(pull->ride (d/pull (d/db conn) ride-pull [:ride/id %])))
         (sort-by :id)))
  (inspection-of [_ id]
    (dec* (d/q '[:find ?p . :in $ ?rid
                :where [?k :inspection/ride-id ?rid] [?k :inspection/payload ?p]]
              (d/db conn) id)))
  (assessment-of [_ ride-id]
    (dec* (d/q '[:find ?p . :in $ ?rid
                :where [?a :assessment/ride-id ?rid] [?a :assessment/payload ?p]]
              (d/db conn) ride-id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (reopening-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :reopening/seq ?s] [?e :reopening/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (next-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :sequence/jurisdiction ?j] [?e :sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (ride-already-reopened? [s ride-id]
    (boolean (:reopened? (ride s ride-id))))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :ride/upsert
      (d/transact! conn [(ride->tx value)])

      :assessment/set
      (d/transact! conn [{:assessment/ride-id (first path) :assessment/payload (enc payload)}])

      :inspection/set
      (d/transact! conn [{:inspection/ride-id (first path) :inspection/payload (enc payload)}])

      :ride/mark-reopened
      (let [ride-id (first path)
            {:keys [result ride-patch]} (reopen-ride! s ride-id)
            jurisdiction (:jurisdiction (ride s ride-id))
            next-n (inc (next-sequence s jurisdiction))]
        (d/transact! conn
                     [(ride->tx (assoc ride-patch :id ride-id))
                      {:sequence/jurisdiction jurisdiction :sequence/next next-n}
                      {:reopening/seq (count (reopening-history s)) :reopening/record (enc (get result "record"))}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-rides [s rides]
    (when (seq rides) (d/transact! conn (mapv ride->tx (vals rides)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:rides ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [rides]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (with-rides s rides))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo ride set -- the Datomic-backed
  analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
