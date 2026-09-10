(ns ofsup.operation-test
  "Integration tests for `ofsup.operation` -- build the REAL compiled
  `langgraph.graph` (via `ofsup.operation/build`) and drive it
  end-to-end through `langgraph.graph/run*`, exactly the way
  `ofsup.sim/-main` does, for all three terminal routes (commit / hard
  hold / escalate-then-approve / escalate-then-reject). Closes the gap
  left by `governor_contract_test.clj` (which only calls
  `governor/check` directly, never the compiled graph) and
  `governor_self_trip_test.clj` (advisor-only) -- neither exercises
  `:intake -> :advise -> :govern -> :decide -> :commit|:request-
  approval|:hold`, the `interrupt-before` pause/resume, or that a
  commit/hold genuinely lands in the REAL `ofsup.store` audit ledger
  (not just the transient `:audit` channel)."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [ofsup.operation :as op]
            [ofsup.store :as store]))

(def ^:private operator
  {:actor-id "op-1" :actor-role :office-support-operations-coordinator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- reject! [actor tid]
  (g/run* actor {:approval {:status :rejected :by "op-1"}} {:thread-id tid :resume? true}))

(deftest commit-path-log-service-record
  (testing "job-1 has a spec-basis on file (USA) -- a clean, high-
            confidence :log-service-record proposal runs the REAL
            compiled graph end to end and auto-commits at phase 3"
    (let [db (store/seed-db)
          actor (op/build db)
          result (exec-op actor "t-commit"
                          {:op :log-service-record :subject "job-1"
                           :patch {:id "job-1" :client "Integration Test Co"}}
                          operator)
          state (:state result)]
      (is (= :done (:status result)))
      (is (= :commit (:disposition state)))
      (is (false? (:hard? (:verdict state))))
      (is (false? (:escalate? (:verdict state))))
      (is (= "Integration Test Co" (:client (store/job db "job-1")))
          "the commit genuinely mutated the SSoT, not just the transient graph state")
      (testing "the commit is durably recorded in the REAL audit ledger"
        (let [ledger (store/ledger db)]
          (is (= 1 (count ledger)))
          (is (= :committed (:t (first ledger))))
          (is (= :log-service-record (:op (first ledger))))
          (is (= "job-1" (:subject (first ledger)))))))))

(deftest hard-hold-path-unverified-record
  (testing "job-2's client/job record was never independently verified/
            registered -- the real graph HARD-holds and terminates,
            never reaching :request-approval"
    (let [db (store/seed-db)
          actor (op/build db)
          result (exec-op actor "t-hold"
                          {:op :schedule-service-operation :subject "job-2"}
                          operator)
          state (:state result)]
      (is (= :done (:status result)))
      (is (= :hold (:disposition state)))
      (is (true? (:hard? (:verdict state))))
      (is (some #(= :record-not-verified (:rule %)) (:violations (:verdict state))))
      (is (false? (:scheduled? (store/job db "job-2")))
          "a HARD hold never mutates the SSoT")
      (testing "the HARD violation is ALSO durably recorded to the REAL
                audit ledger by the :hold node"
        (let [ledger (store/ledger db)]
          (is (= 1 (count ledger)))
          (is (= :governor-hold (:t (first ledger))))
          (is (= :hold (:disposition (first ledger))))
          (is (= "job-2" (:subject (first ledger))))
          (is (= [:record-not-verified] (:basis (first ledger)))))))))

(deftest escalate-then-approve-commits-confidentiality-concern
  (testing ":flag-confidentiality-concern ALWAYS escalates (never a
            member of any phase's :auto set, and unconditionally
            high-stakes) -- the real graph GENUINELY interrupts
            (checkpointed) at :request-approval; a human approve!
            resumes the SAME compiled graph and commits via the
            actual :request-approval -> :commit edge"
    (let [db (store/seed-db)
          actor (op/build db)
          held (exec-op actor "t-escalate-approve"
                        {:op :flag-confidentiality-concern :subject "job-1"
                         :note "unattended printout of a financial statement"}
                        operator)
          held-state (:state held)]
      (is (= :interrupted (:status held)))
      (is (= [:request-approval] (:frontier held)))
      (is (true? (:escalate? (:verdict held-state))))
      (is (true? (:high-stakes? (:verdict held-state))))
      (is (false? (:confidentiality-concern-raised? (store/job db "job-1")))
          "not yet committed -- awaiting human sign-off")
      (is (empty? (store/ledger db)) "the ORIGINAL store has no ledger entry yet either")
      (let [approved (approve! actor "t-escalate-approve")
            approved-state (:state approved)]
        (is (= :done (:status approved)))
        (is (= :commit (:disposition approved-state)))
        (is (true? (:confidentiality-concern-raised? (store/job db "job-1"))))
        (testing "approve! also genuinely persists to the REAL audit ledger"
          (let [ledger (store/ledger db)]
            (is (= 1 (count ledger)))
            (is (= :committed (:t (first ledger))))
            (is (= :flag-confidentiality-concern (:op (first ledger))))))))))

(deftest escalate-then-reject-holds-high-cost-supply-order
  (testing "job-3's supply-order estimate (900 USD) exceeds the
            governor's high-cost-supply threshold (500 USD) -- the
            real graph escalates; a human reject! resumes the SAME
            compiled graph and routes to :hold, never :commit"
    (let [db (store/seed-db)
          actor (op/build db)
          held (exec-op actor "t-escalate-reject"
                        {:op :coordinate-supply-order :subject "job-3"
                         :estimated-cost-usd 900}
                        operator)
          held-state (:state held)]
      (is (= :interrupted (:status held)))
      (is (= [:request-approval] (:frontier held)))
      (is (true? (:escalate? (:verdict held-state))))
      (is (true? (:high-stakes? (:verdict held-state))))
      (let [rejected (reject! actor "t-escalate-reject")
            rejected-state (:state rejected)]
        (is (= :done (:status rejected)))
        (is (= :hold (:disposition rejected-state)))
        (is (false? (:supply-coordination-open? (store/job db "job-3")))
            "a rejected escalation never mutates the SSoT")
        (testing "the rejection is durably recorded to the REAL audit ledger"
          (let [ledger (store/ledger db)]
            (is (= 1 (count ledger)))
            (is (= :approval-rejected (:t (first ledger))))
            (is (= :hold (:disposition (first ledger))))))))))
