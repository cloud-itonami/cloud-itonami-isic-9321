(ns parksafety.registry-test
  (:require [clojure.test :refer [deftest is]]
            [parksafety.registry :as r]))

;; ----------------------------- operators-sufficient? -----------------------------

(deftest operators-sufficient-when-at-or-above-minimum
  (is (r/operators-sufficient? {:certified-operators-on-duty 2 :minimum-operators-required 2}))
  (is (r/operators-sufficient? {:certified-operators-on-duty 3 :minimum-operators-required 2}))
  (is (not (r/operators-sufficient? {:certified-operators-on-duty 1 :minimum-operators-required 2}))))

(deftest operators-not-sufficient-when-missing
  (is (not (r/operators-sufficient? {:minimum-operators-required 2}))))

;; ----------------------------- register-ride-reopening -----------------------------

(deftest ride-reopening-is-a-draft-not-a-real-reopening
  (let [result (r/register-ride-reopening "ride-1" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest ride-reopening-assigns-reopening-number
  (let [result (r/register-ride-reopening "ride-1" "JPN" 7)]
    (is (= (get result "reopening_number") "JPN-RDE-000007"))
    (is (= (get-in result ["record" "ride_id"]) "ride-1"))
    (is (= (get-in result ["record" "kind"]) "ride-reopening-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest ride-reopening-validation-rules
  (is (thrown? Exception (r/register-ride-reopening "" "JPN" 0)))
  (is (thrown? Exception (r/register-ride-reopening "ride-1" "" 0)))
  (is (thrown? Exception (r/register-ride-reopening "ride-1" "JPN" -1))))

(deftest reopening-history-is-append-only
  (let [r1 (r/register-ride-reopening "ride-1" "JPN" 0)
        hist (r/append [] r1)
        r2 (r/register-ride-reopening "ride-2" "JPN" 1)
        hist2 (r/append hist r2)]
    (is (= 2 (count hist2)))
    (is (= "JPN-RDE-000000" (get-in hist2 [0 "record_id"])))
    (is (= "JPN-RDE-000001" (get-in hist2 [1 "record_id"])))))
