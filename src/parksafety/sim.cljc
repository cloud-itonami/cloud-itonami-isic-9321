(ns parksafety.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean ride through
  intake -> jurisdiction assessment -> post-hold inspection screening
  -> ride-reopening proposal (always escalates) -> human approval ->
  commit, then shows four HARD holds (a jurisdiction with no spec-
  basis, a failed post-hold inspection screening that never reaches a
  human, a ride with fewer certified operators on duty than its own
  minimum staffing requirement, and a double reopening of an already-
  reopened ride) that never reach a human at all, and prints the
  audit ledger + the draft ride-reopening records."
  (:require [langgraph.graph :as g]
            [parksafety.store :as store]
            [parksafety.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :licensed-ride-operator :phase 3})

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== ride/intake ride-1 (JPN, clean; inspection passed, 3 of 2 minimum operators on duty) ==")
    (println (exec! actor "t1" {:op :ride/intake :subject "ride-1"
                                :patch {:id "ride-1" :ride-name "Sakura Coaster"}} operator))

    (println "== jurisdiction/assess ride-1 (escalates -- human approves) ==")
    (println (exec! actor "t2" {:op :jurisdiction/assess :subject "ride-1"} operator))
    (println (approve! actor "t2"))

    (println "== inspection/screen ride-1 (clean; escalates -- human approves) ==")
    (println (exec! actor "t3" {:op :inspection/screen :subject "ride-1"} operator))
    (println (approve! actor "t3"))

    (println "== ride/reopen ride-1 (always escalates -- actuation/reopen-ride) ==")
    (let [r (exec! actor "t4" {:op :ride/reopen :subject "ride-1"} operator)]
      (println r)
      (println "-- human licensed ride operator approves --")
      (println (approve! actor "t4")))

    (println "== jurisdiction/assess ride-2 (no spec-basis -> HARD hold) ==")
    (println (exec! actor "t5" {:op :jurisdiction/assess :subject "ride-2" :no-spec? true} operator))

    (println "== inspection/screen ride-3 (failed post-hold inspection -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t6" {:op :inspection/screen :subject "ride-3"} operator))

    (println "== jurisdiction/assess ride-4 (escalates -- human approves; sets up the operators-insufficient test) ==")
    (println (exec! actor "t7" {:op :jurisdiction/assess :subject "ride-4"} operator))
    (println (approve! actor "t7"))

    (println "== ride/reopen ride-4 (1 of 3 minimum certified operators on duty -> HARD hold) ==")
    (println (exec! actor "t8" {:op :ride/reopen :subject "ride-4"} operator))

    (println "== ride/reopen ride-1 AGAIN (double-reopening of an already-reopened ride -> HARD hold) ==")
    (println (exec! actor "t9" {:op :ride/reopen :subject "ride-1"} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft ride-reopening records ==")
    (doseq [r (store/reopening-history db)] (println r))))
