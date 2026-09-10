(ns parksafety.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a configuration
  change, not a rewrite -- see `cloud-itonami-isic-6511`'s
  `underwriting.store-contract-test` for the same pattern on the sibling
  actor."
  (:require [clojure.test :refer [deftest is testing]]
            [parksafety.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "Sakura Coaster" (:ride-name (store/ride s "ride-1"))))
      (is (= "JPN" (:jurisdiction (store/ride s "ride-1"))))
      (is (true? (:post-hold-inspection-passed? (store/ride s "ride-1"))))
      (is (= 3 (:certified-operators-on-duty (store/ride s "ride-1"))))
      (is (= 2 (:minimum-operators-required (store/ride s "ride-1"))))
      (is (false? (:post-hold-inspection-passed? (store/ride s "ride-3"))))
      (is (= 1 (:certified-operators-on-duty (store/ride s "ride-4"))))
      (is (false? (:reopened? (store/ride s "ride-1"))))
      (is (= ["ride-1" "ride-2" "ride-3" "ride-4"]
             (mapv :id (store/all-rides s))))
      (is (nil? (store/inspection-of s "ride-1")))
      (is (nil? (store/assessment-of s "ride-1")))
      (is (= [] (store/ledger s)))
      (is (= [] (store/reopening-history s)))
      (is (zero? (store/next-sequence s "JPN")))
      (is (false? (store/ride-already-reopened? s "ride-1"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial upsert merges, preserving untouched fields"
        (store/commit-record! s {:effect :ride/upsert
                                 :value {:id "ride-1" :ride-name "Sakura Coaster"}})
        (is (= "Sakura Coaster" (:ride-name (store/ride s "ride-1"))))
        (is (= 3 (:certified-operators-on-duty (store/ride s "ride-1"))) "operator count preserved"))
      (testing "assessment / inspection payloads commit and read back"
        (store/commit-record! s {:effect :assessment/set :path ["ride-1"]
                                 :payload {:jurisdiction "JPN" :checklist ["a" "b"]}})
        (is (= {:jurisdiction "JPN" :checklist ["a" "b"]} (store/assessment-of s "ride-1")))
        (store/commit-record! s {:effect :inspection/set :path ["ride-1"]
                                 :payload {:ride-id "ride-1" :verdict :passed}})
        (is (= {:ride-id "ride-1" :verdict :passed} (store/inspection-of s "ride-1"))))
      (testing "ride reopening drafts a reopening record and advances the sequence"
        (store/commit-record! s {:effect :ride/mark-reopened :path ["ride-1"]})
        (is (= "JPN-RDE-000000" (get (first (store/reopening-history s)) "record_id")))
        (is (= "ride-reopening-draft" (get (first (store/reopening-history s)) "kind")))
        (is (true? (:reopened? (store/ride s "ride-1"))))
        (is (= 1 (count (store/reopening-history s))))
        (is (= 1 (store/next-sequence s "JPN")))
        (is (true? (store/ride-already-reopened? s "ride-1")))
        (is (false? (store/ride-already-reopened? s "ride-2"))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/ride s "nope")))
    (is (= [] (store/all-rides s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/reopening-history s)))
    (is (zero? (store/next-sequence s "JPN")))
    (store/with-rides s {"x" {:id "x" :ride-name "n" :hold-reason "r"
                              :post-hold-inspection-passed? true :certified-operators-on-duty 2
                              :minimum-operators-required 2 :reopened? false
                              :jurisdiction "JPN" :status :intake}})
    (is (= "n" (:ride-name (store/ride s "x"))))))
