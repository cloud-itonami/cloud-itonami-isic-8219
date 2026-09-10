(ns ofsup.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:flag-confidentiality-concern` must NEVER be a member
  of any phase's `:auto` set. UNLIKE `cloud-itonami-isic-8020`, this
  domain also asserts the POSITIVE invariant that `:log-service-
  record`/`:schedule-service-operation`/`:coordinate-supply-order` ARE
  phase-3 auto-eligible (see `ofsup.governor` ns docstring `UNLIKE`)."
  (:require [clojure.test :refer [deftest is testing]]
            [ofsup.phase :as phase]))

(deftest flag-confidentiality-concern-never-auto-at-any-phase
  (testing "structural invariant: flagging a concern must ALWAYS reach a human -- never in any phase's :auto set"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :flag-confidentiality-concern))
          (str "phase " n " must not auto-commit :flag-confidentiality-concern")))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-the-no-privacy-finalization-ops
  (testing "log-service-record/schedule-service-operation/coordinate-supply-order carry no direct data-privacy-finalization/document-release weight of their own -- auto-eligible; flag-confidentiality-concern is the sole permanent exception"
    (is (= #{:log-service-record :schedule-service-operation :coordinate-supply-order}
           (:auto (get phase/phases 3))))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :log-service-record} :hold)))))

(deftest gate-auto-commits-a-clean-auto-eligible-write
  (is (= :commit (:disposition (phase/gate 3 {:op :log-service-record} :commit))))
  (is (= :commit (:disposition (phase/gate 3 {:op :schedule-service-operation} :commit))))
  (is (= :commit (:disposition (phase/gate 3 {:op :coordinate-supply-order} :commit)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :flag-confidentiality-concern} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :log-service-record} :commit)))))
