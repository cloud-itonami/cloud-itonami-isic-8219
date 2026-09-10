(ns ofsup.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a
  configuration change, not a rewrite -- see `cloud-itonami-isic-8020`'s
  own `secsys.store-contract-test` for the same pattern on a sibling
  actor."
  (:require [clojure.test :refer [deftest is testing]]
            [ofsup.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "USA" (:jurisdiction (store/job s "job-1"))))
      (is (true? (:registered? (store/job s "job-1"))))
      (is (false? (:registered? (store/job s "job-2"))))
      (is (true? (:requires-registration? (store/job s "job-3"))))
      (is (false? (:registration-confirmed? (store/job s "job-3"))))
      (is (true? (:confidentiality-concern-raised? (store/job s "job-4"))))
      (is (false? (:confidentiality-concern-resolved? (store/job s "job-4"))))
      (is (true? (:supply-coordination-open? (store/job s "job-5"))))
      (is (false? (:scheduled? (store/job s "job-1"))))
      (is (= ["job-1" "job-2" "job-3" "job-4" "job-5"]
             (mapv :id (store/all-jobs s))))
      (is (= [] (store/ledger s)))
      (is (= [] (store/schedule-history s)))
      (is (= [] (store/supply-history s))
          "the pre-seeded job-5 flag is not itself a committed-history entry")
      (is (zero? (store/next-schedule-sequence s "USA")))
      (is (zero? (store/next-supply-sequence s "USA")))
      (is (false? (store/job-already-scheduled? s "job-1")))
      (is (true? (store/job-supply-already-open? s "job-5"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial upsert merges, preserving untouched fields"
        (store/commit-record! s {:action :job/log :path ["job-1"]
                                 :value {:patch {:id "job-1" :client "Updated Retail Co"}
                                        :spec-basis "https://example.test/spec" :legal-basis "Test Act"}})
        (is (= "Updated Retail Co" (:client (store/job s "job-1"))))
        (is (= :photocopy (:document-type (store/job s "job-1"))) "unrelated field preserved")
        (is (true? (:registered? (store/job s "job-1")))))
      (testing "confidentiality-concern flag commits"
        (store/commit-record! s {:action :job/flag-confidentiality-concern :path ["job-1"]
                                 :value {:note "reported an unattended printout"}})
        (is (true? (:confidentiality-concern-raised? (store/job s "job-1"))))
        (is (false? (:confidentiality-concern-resolved? (store/job s "job-1")))))
      (testing "service schedule drafts a record and advances the schedule sequence"
        (store/commit-record! s {:action :job/mark-scheduled :path ["job-1"]})
        (is (= "USA-SVC-000000" (get (first (store/schedule-history s)) "record_id")))
        (is (= "service-schedule-draft" (get (first (store/schedule-history s)) "kind")))
        (is (true? (:scheduled? (store/job s "job-1"))))
        (is (= 1 (count (store/schedule-history s))))
        (is (= 1 (store/next-schedule-sequence s "USA")))
        (is (true? (store/job-already-scheduled? s "job-1"))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/job s "nope")))
    (is (= [] (store/all-jobs s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/schedule-history s)))
    (is (= [] (store/supply-history s)))
    (is (zero? (store/next-schedule-sequence s "USA")))
    (is (zero? (store/next-supply-sequence s "USA")))
    (store/with-jobs s {"x" {:id "x" :client "c"
                             :document-type :photocopy :document-count 10 :turnaround-hours 2
                             :requires-registration? false
                             :registered? true :spec-basis "https://example.test/spec" :legal-basis "Test Act"
                             :registration-confirmed? false
                             :confidentiality-concern-raised? false :confidentiality-concern-resolved? false
                             :scheduled? false :schedule-number nil
                             :supply-coordination-open? false :supply-coordination-number nil
                             :jurisdiction "USA" :status :intake}})
    (is (= "c" (:client (store/job s "x"))))))
