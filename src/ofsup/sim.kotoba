(ns ofsup.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean job through
  record logging (auto-commit) -> service-schedule coordination
  (auto-commit, clean) -> low-cost supply-order coordination
  (auto-commit) -> high-cost supply-order coordination (escalate/
  approve/commit, different job) -> confidentiality-concern flag
  (always escalate/approve/commit), then shows HARD-hold scenarios: a
  jurisdiction with no spec-basis, an unregistered client-job record,
  an unconfirmed document-preparer registration, an open
  confidentiality concern, an already-open supply-order coordination,
  a double-schedule, and a double supply-order coordination.

  Like every sibling actor's new checks, this actor's new checks
  (`record-not-verified?`, `registration-unconfirmed?`, `open-
  confidentiality-concern?`) are evaluated directly at `:schedule-
  service-operation`/`:coordinate-supply-order` time rather than via a
  separate screening op -- a real scheduling/coordination decision
  validates a registered record, a confirmed document-preparer
  registration and a clear confidentiality-concern status at the point
  of the proposal itself. Each check is still exercised directly and
  independently below, one job per HARD-hold scenario, following the
  SAME 'exercise the failure mode directly, never only via a happy-
  path actuation' discipline every sibling since `parksafety`'s
  ADR-2607071922 Decision 5 establishes. The purely structural checks
  (`effect-not-propose`/`op-not-allowlisted`/`action-not-allowlisted`/
  `scope-exclusion-violation`) are never reachable via this well-
  behaved mock advisor's own output -- they are exercised directly in
  `test/ofsup/governor_contract_test.clj` against a hand-crafted
  adversarial proposal instead."
  (:require [langgraph.graph :as g]
            [ofsup.store :as store]
            [ofsup.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :office-support-operations-coordinator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== log-service-record job-1 (USA, clean, auto-commit) ==")
    (println (exec-op actor "t1" {:op :log-service-record :subject "job-1"
                                  :patch {:id "job-1" :client "Updated Retail Co"}} operator))

    (println "== schedule-service-operation job-1 (clean -> auto-commit, no dispatch/document-release weight) ==")
    (println (exec-op actor "t2" {:op :schedule-service-operation :subject "job-1"} operator))

    (println "== coordinate-supply-order job-1 (low-cost routine reorder -> auto-commit) ==")
    (println (exec-op actor "t3" {:op :coordinate-supply-order :subject "job-1" :estimated-cost-usd 120} operator))

    (println "== coordinate-supply-order job-3 (high-cost equipment order -> always escalates -- office-support/high-cost-supply) ==")
    (let [r (exec-op actor "t4" {:op :coordinate-supply-order :subject "job-3" :estimated-cost-usd 900} operator)]
      (println r)
      (println "-- human office-support operations coordinator approves --")
      (println (approve! actor "t4")))

    (println "== flag-confidentiality-concern job-1 (always escalates -- office-support/flag-concern) ==")
    (let [r (exec-op actor "t5" {:op :flag-confidentiality-concern :subject "job-1"
                                 :note "customer reported an unattended printout of a financial statement"} operator)]
      (println r)
      (println "-- human office-support operations coordinator approves --")
      (println (approve! actor "t5")))

    (println "== log-service-record job-6 (no spec-basis -> HARD hold) ==")
    (println (exec-op actor "t6" {:op :log-service-record :subject "job-6"
                                  :patch {:id "job-6" :client "New Client Co"} :no-spec? true} operator))

    (println "== schedule-service-operation job-2 (record never verified/registered -> HARD hold) ==")
    (println (exec-op actor "t7" {:op :schedule-service-operation :subject "job-2"} operator))

    (println "== schedule-service-operation job-3 (registration-requiring, unconfirmed -> HARD hold) ==")
    (println (exec-op actor "t8" {:op :schedule-service-operation :subject "job-3"} operator))

    (println "== schedule-service-operation job-4 (open confidentiality concern -> HARD hold) ==")
    (println (exec-op actor "t9" {:op :schedule-service-operation :subject "job-4"} operator))

    (println "== coordinate-supply-order job-5 (already an open supply-order coordination -> HARD hold) ==")
    (println (exec-op actor "t10" {:op :coordinate-supply-order :subject "job-5" :estimated-cost-usd 120} operator))

    (println "== schedule-service-operation job-1 AGAIN (double-schedule -> HARD hold) ==")
    (println (exec-op actor "t11" {:op :schedule-service-operation :subject "job-1"} operator))

    (println "== coordinate-supply-order job-1 AGAIN (double-coordinate -> HARD hold) ==")
    (println (exec-op actor "t12" {:op :coordinate-supply-order :subject "job-1" :estimated-cost-usd 120} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft service-schedule records ==")
    (doseq [r (store/schedule-history db)] (println r))

    (println "== draft supply-order records ==")
    (doseq [r (store/supply-history db)] (println r))))
