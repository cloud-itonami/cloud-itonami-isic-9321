(ns parksafety.governor-contract-test
  "The governor contract as executable tests -- the amusement-park
  analog of `cloud-itonami-isic-6512`'s `casualty.governor-contract-
  test`. The single invariant under test:

    ParkOps-LLM never reopens a ride the Ride Safety Governor would
    reject, `:ride/reopen` NEVER auto-commits at any phase, `:ride/
    intake` (no direct capital risk) MAY auto-commit when clean, and
    every decision (commit OR hold) leaves exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [parksafety.store :as store]
            [parksafety.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :licensed-ride-operator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- assess!
  "Walks `subject` through assess -> approve, leaving an assessment on
  file. Uses distinct thread-ids per call site by suffixing
  `tid-prefix`."
  [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-assess") {:op :jurisdiction/assess :subject subject} operator)
  (approve! actor (str tid-prefix "-assess")))

(deftest clean-intake-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :ride/intake :subject "ride-1"
                   :patch {:id "ride-1" :ride-name "Sakura Coaster"}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= "Sakura Coaster" (:ride-name (store/ride db "ride-1"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest jurisdiction-assess-always-needs-approval
  (testing "assess is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :jurisdiction/assess :subject "ride-1"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (some? (store/assessment-of db "ride-1")))))))

(deftest fabricated-jurisdiction-is-held
  (testing "a jurisdiction/assess proposal with no official spec-basis -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :jurisdiction/assess :subject "ride-1" :no-spec? true} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-spec-basis} (-> (store/ledger db) first :basis)))
      (is (nil? (store/assessment-of db "ride-1")) "no assessment written"))))

(deftest ride-reopen-without-assessment-is-held
  (testing "ride/reopen before any jurisdiction assessment -> HOLD (evidence incomplete)"
    (let [[db actor] (fresh)
          res (exec-op actor "t4" {:op :ride/reopen :subject "ride-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:evidence-incomplete} (-> (store/ledger db) first :basis))))))

(deftest inspection-not-passed-is-held-and-unoverridable
  (testing "a failed post-hold inspection on a ride -> HOLD, and never reaches request-approval"
    (let [[db actor] (fresh)
          res (exec-op actor "t5" {:op :inspection/screen :subject "ride-3"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:inspection-not-passed} (-> (store/ledger db) first :basis)))
      (is (nil? (store/inspection-of db "ride-3")) "no clearance written"))))

(deftest operators-insufficient-is-held
  (testing "a ride with fewer certified operators on duty than its own minimum staffing requirement -> HOLD"
    (let [[db actor] (fresh)
          _ (assess! actor "t6pre" "ride-4")
          res (exec-op actor "t6" {:op :ride/reopen :subject "ride-4"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:operators-insufficient} (-> (store/ledger db) last :basis)))
      (is (empty? (store/reopening-history db))))))

(deftest ride-reopen-always-escalates-then-human-decides
  (testing "a clean, fully-assessed, passed-inspection, sufficiently-staffed ride still ALWAYS interrupts for human approval -- actuation/reopen-ride is never auto"
    (let [[db actor] (fresh)
          _ (assess! actor "t7pre" "ride-1")
          r1 (exec-op actor "t7" {:op :ride/reopen :subject "ride-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, reopening record drafted"
        (let [r2 (approve! actor "t7")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:reopened? (store/ride db "ride-1"))))
          (is (= 1 (count (store/reopening-history db))) "one draft reopening record")))))
  (testing "reject -> hold, nothing reopened"
    (let [[db actor] (fresh)
          _ (assess! actor "t8pre" "ride-1")
          _ (exec-op actor "t8" {:op :ride/reopen :subject "ride-1"} operator)
          r2 (g/run* actor {:approval {:status :rejected :by "op-1"}}
                     {:thread-id "t8" :resume? true})]
      (is (= :hold (get-in r2 [:state :disposition])))
      (is (empty? (store/reopening-history db)) "nothing reopened on reject"))))

(deftest ride-reopen-double-reopening-is-held
  (testing "reopening the same ride twice -> HOLD on the second attempt, even though the figures match cleanly"
    (let [[db actor] (fresh)
          _ (assess! actor "t9pre" "ride-1")
          _ (exec-op actor "t9a" {:op :ride/reopen :subject "ride-1"} operator)
          _ (approve! actor "t9a")
          res (exec-op actor "t9" {:op :ride/reopen :subject "ride-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-reopened} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/reopening-history db))) "still only the one earlier reopening"))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :ride/intake :subject "ride-1"
                          :patch {:id "ride-1" :ride-name "Sakura Coaster"}} operator)
      (exec-op actor "b" {:op :jurisdiction/assess :subject "ride-1" :no-spec? true} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
