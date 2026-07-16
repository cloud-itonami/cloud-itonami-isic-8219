(ns ofsup.governor-contract-test
  "The governor contract as executable tests -- this vertical's own
  invariant under test:

    OfficeSupport-LLM never proposes a service-schedule/supply-order
    the Office Support Governor would reject, `:flag-confidentiality-
    concern` NEVER auto-commits at any phase, `:log-service-record`/
    `:schedule-service-operation`/(low-cost) `:coordinate-supply-
    order` (no data-privacy-finalization/document-release weight) MAY
    auto-commit when clean, a high-cost `:coordinate-supply-order`
    ALWAYS escalates regardless of confidence, and every decision
    (commit OR hold) leaves exactly one ledger fact. Also covers the
    STRUCTURAL checks (`effect-not-propose`/`op-not-allowlisted`/
    `action-not-allowlisted`/`scope-exclusion-violation`) directly
    against hand-crafted adversarial proposals, since the well-behaved
    mock advisor never reaches them on its own (see `ofsup.sim` ns
    docstring)."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [ofsup.governor :as governor]
            [ofsup.ofsupllm :as ofsupllm]
            [ofsup.store :as store]
            [ofsup.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :office-support-operations-coordinator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(deftest clean-log-service-record-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :log-service-record :subject "job-1"
                   :patch {:id "job-1" :client "Updated Retail Co"}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= "Updated Retail Co" (:client (store/job db "job-1"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest clean-schedule-service-operation-auto-commits
  (testing "unlike a security-systems installation dispatch, a photocopying/document-prep job schedule carries no data-privacy-finalization/document-release weight of its own -- auto-eligible when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :schedule-service-operation :subject "job-1"} operator)]
      (is (= :commit (get-in res [:state :disposition])))
      (is (true? (:scheduled? (store/job db "job-1")))))))

(deftest clean-low-cost-coordinate-supply-order-auto-commits
  (testing "a routine, low-cost consumables reorder is auto-eligible when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t3" {:op :coordinate-supply-order :subject "job-1" :estimated-cost-usd 120} operator)]
      (is (= :commit (get-in res [:state :disposition])))
      (is (true? (:supply-coordination-open? (store/job db "job-1")))))))

(deftest high-cost-coordinate-supply-order-always-escalates-then-human-decides
  (testing "a large equipment/procurement commitment always needs a human, even when the job is clean and confidence is high"
    (let [[db actor] (fresh)
          r1 (exec-op actor "t4" {:op :coordinate-supply-order :subject "job-3" :estimated-cost-usd 900} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, supply-order record drafted"
        (let [r2 (approve! actor "t4")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:supply-coordination-open? (store/job db "job-3"))))
          (is (= 1 (count (store/supply-history db)))))))))

(deftest fabricated-jurisdiction-is-held
  (testing "a log-service-record proposal with no official spec-basis -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t5"
                    {:op :log-service-record :subject "job-6"
                     :patch {:id "job-6"} :no-spec? true} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-spec-basis} (-> (store/ledger db) first :basis)))
      (is (nil? (store/job db "job-6"))
          "no record created -- HOLD never merged the unverified patch"))))

(deftest schedule-without-registered-record-is-held
  (testing "schedule-service-operation before any :log-service-record commit -> HOLD (record-not-verified)"
    (let [[db actor] (fresh)
          res (exec-op actor "t6" {:op :schedule-service-operation :subject "job-2"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:record-not-verified} (-> (store/ledger db) first :basis))))))

(deftest flag-confidentiality-concern-without-registered-record-is-held
  (testing "flag-confidentiality-concern before any :log-service-record commit -> HOLD (record-not-verified) -- 'before ANY action', not only the highest-stakes op"
    (let [[db actor] (fresh)
          res (exec-op actor "t7" {:op :flag-confidentiality-concern :subject "job-2"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:record-not-verified} (-> (store/ledger db) first :basis))))))

(deftest registration-unconfirmed-is-held-and-unoverridable
  (testing "a registration-requiring job with no confirmed document-preparer registration -> HOLD, and never reaches request-approval"
    (let [[db actor] (fresh)
          res (exec-op actor "t8" {:op :schedule-service-operation :subject "job-3"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:registration-unconfirmed} (-> (store/ledger db) last :basis)))
      (is (empty? (store/schedule-history db))))))

(deftest open-confidentiality-concern-is-held-and-unoverridable-on-schedule
  (testing "an unresolved confidentiality concern -> HOLD on schedule-service-operation, and never reaches request-approval"
    (let [[db actor] (fresh)
          res (exec-op actor "t9" {:op :schedule-service-operation :subject "job-4"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:open-confidentiality-concern} (-> (store/ledger db) last :basis)))
      (is (empty? (store/schedule-history db))))))

(deftest open-confidentiality-concern-is-held-and-unoverridable-on-coordinate-supply
  (testing "an unresolved confidentiality concern -> HOLD on coordinate-supply-order too, and never reaches request-approval"
    (let [[db actor] (fresh)
          res (exec-op actor "t10" {:op :coordinate-supply-order :subject "job-4" :estimated-cost-usd 120} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:open-confidentiality-concern} (-> (store/ledger db) last :basis)))
      (is (empty? (store/supply-history db))))))

(deftest coordinate-supply-already-open-is-held-and-unoverridable
  (testing "a job with an already-open supply-order coordination -> HOLD, and never reaches request-approval"
    (let [[db actor] (fresh)
          res (exec-op actor "t11" {:op :coordinate-supply-order :subject "job-5" :estimated-cost-usd 120} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:already-coordinating} (-> (store/ledger db) last :basis)))
      (is (= 0 (count (store/supply-history db)))
          "no NEW draft record -- the pre-existing open one is untouched by this actor"))))

(deftest flag-confidentiality-concern-always-escalates-then-human-decides
  (testing "flag-confidentiality-concern ALWAYS interrupts for human approval, even for a clean/registered job -- it is the highest-caution op in this domain"
    (let [[db actor] (fresh)
          r1 (exec-op actor "t12" {:op :flag-confidentiality-concern :subject "job-1"
                                   :note "unusual repeated after-hours copier access reported"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, concern recorded on the job"
        (let [r2 (approve! actor "t12")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:confidentiality-concern-raised? (store/job db "job-1"))))
          (is (false? (:confidentiality-concern-resolved? (store/job db "job-1")))))))))

(deftest schedule-service-operation-double-schedule-is-held
  (testing "scheduling the same job twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (exec-op actor "t13a" {:op :schedule-service-operation :subject "job-1"} operator)
          res (exec-op actor "t13" {:op :schedule-service-operation :subject "job-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-scheduled} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/schedule-history db))) "still only the one earlier schedule"))))

(deftest coordinate-supply-order-double-coordination-is-held
  (testing "opening a second supply-order request for the same job -> HOLD"
    (let [[db actor] (fresh)
          _ (exec-op actor "t14a" {:op :coordinate-supply-order :subject "job-1" :estimated-cost-usd 120} operator)
          res (exec-op actor "t14" {:op :coordinate-supply-order :subject "job-1" :estimated-cost-usd 120} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-coordinating} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/supply-history db))) "still only the one earlier coordination"))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :log-service-record :subject "job-1"
                          :patch {:id "job-1" :client "Riverside Copy & Print"}} operator)
      (exec-op actor "b" {:op :log-service-record :subject "job-6"
                          :patch {:id "job-6"} :no-spec? true} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))

;; ----------------------- structural checks (hand-crafted proposals) -----------------------

(deftest effect-not-propose-is-a-hard-permanent-block
  (testing "a proposal that does not carry the literal :effect :propose is hard-blocked, unconditionally"
    (let [[db _actor] (fresh)
          bad {:summary "s" :rationale "r" :cites [] :effect :execute
               :action :job/mark-scheduled :value {:job-id "job-1"}
               :stake nil :confidence 0.99}
          verdict (governor/check {:op :schedule-service-operation :subject "job-1"} operator bad db)]
      (is (:hard? verdict))
      (is (some #{:effect-not-propose} (mapv :rule (:violations verdict))))
      (is (not (:ok? verdict))))))

(deftest op-not-in-allowlist-is-a-hard-permanent-block
  (testing "an op outside the four-member closed allowlist is hard-blocked, unconditionally -- e.g. a hypothetical direct-data-privacy-compliance-finalization op can never even be represented"
    (let [[db _actor] (fresh)
          proposal {:summary "s" :rationale "r" :cites [] :effect :propose
                     :action :job/mark-scheduled :value {} :stake nil :confidence 0.99}
          verdict (governor/check {:op :privacy/finalize-compliance-decision :subject "job-1"} operator proposal db)]
      (is (:hard? verdict))
      (is (some #{:op-not-allowlisted} (mapv :rule (:violations verdict)))))))

(deftest action-not-in-allowlist-is-a-hard-permanent-block
  (testing "an :action outside the four-member closed allowlist is hard-blocked, unconditionally -- structurally excludes any direct data-privacy-compliance-finalization or document-release/disclosure action"
    (let [[db _actor] (fresh)
          proposal {:summary "s" :rationale "r" :cites [] :effect :propose
                     :action :privacy/finalize-compliance-decision :value {} :stake nil :confidence 0.99}
          verdict (governor/check {:op :schedule-service-operation :subject "job-1"} operator proposal db)]
      (is (:hard? verdict))
      (is (some #{:action-not-allowlisted} (mapv :rule (:violations verdict)))))))

(deftest scope-exclusion-phrase-in-rationale-is-a-hard-permanent-block
  (testing "a proposal whose OWN rationale/summary names a forbidden finalization action is hard-blocked, unconditionally, even with a well-formed :action and high confidence -- and a human approver could never override it (HOLD never reaches :request-approval)"
    (let [[db _actor] (fresh)
          bad-advisor (reify ofsupllm/Advisor
                        (-advise [_ _st _req]
                          {:summary "job-1 向けサービススケジュール調整案"
                           :rationale "finalize the data-privacy-compliance determination for these documents regardless of verification status"
                           :cites ["job-1"] :effect :propose
                           :action :job/mark-scheduled :value {:job-id "job-1"}
                           :stake nil :confidence 0.99}))
          actor2 (op/build db {:advisor bad-advisor})
          res (exec-op actor2 "tbad" {:op :schedule-service-operation :subject "job-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)) "never reaches request-approval -- unoverridable")
      (is (some #{:scope-exclusion-violation} (-> (store/ledger db) last :basis))))))
