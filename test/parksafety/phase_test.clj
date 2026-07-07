(ns parksafety.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:ride/reopen` must NEVER be a member of any phase's
  `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [parksafety.phase :as phase]))

(deftest ride-reopen-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in the future entries, auto-commits a real ride reopening"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :ride/reopen))
          (str "phase " n " must not auto-commit :ride/reopen")))))

(deftest inspection-screen-never-auto-at-any-phase
  (testing "screening carries no direct capital risk, but is still never auto-eligible, matching every sibling KYC/conflict/independence/surveillance/calibration/credential/integrity/patron/authorization/safety screen"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :inspection/screen))
          (str "phase " n " must not auto-commit :inspection/screen")))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-no-capital-risk-ops
  (testing ":ride/intake carries no direct capital risk -- auto-eligible; it is the ONLY auto-eligible op in this domain"
    (is (= #{:ride/intake} (:auto (get phase/phases 3))))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :ride/intake} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :ride/reopen} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :ride/intake} :commit)))))
