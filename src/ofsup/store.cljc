(ns ofsup.store
  "SSoT for the office-support-services (photocopying / document-
  preparation / mailing) operations-coordination actor, behind a
  `Store` protocol so the backend is a swap, not a rewrite -- the same
  seam every prior `cloud-itonami-isic-*` actor in this fleet uses (see
  e.g. `cloud-itonami-isic-8020`'s own `secsys.store`).

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/ofsup/store_contract_test.clj), which is the whole point: the
  actor, the Office Support Governor and the audit ledger never know
  which SSoT they run on.

  The single entity here is a `job` (a client document-preparation /
  photocopying / mailing job record under coordination).
  `:log-service-record` registers/updates it, `:schedule-service-
  operation` and `:coordinate-supply-order` each apply SEQUENTIALLY to
  the SAME job (a job can be scheduled once its record is verified/
  registered, and separately have supply procurement coordinated),
  with dedicated double-actuation-guard booleans (`:scheduled?`/
  `:supply-coordination-open?`, never a `:status` value) -- the same
  discipline every sibling actor's own guard booleans use.

  The ledger stays append-only on every backend: 'which job was
  screened for an unregistered record, an unconfirmed document-
  preparer registration, or an open confidentiality concern, which job
  was scheduled, which supply order was opened, on what jurisdictional
  basis, approved by whom' is always a query over an immutable log --
  the audit trail a client or regulator trusting an office-support
  operator needs, and the evidence an operator needs if a schedule or
  a supply order is later disputed.

  The EDN-blob codec, `:db.unique/identity` schema builder, and the
  seq-keyed ledger/schedule-history/supply-history event-log read are
  the shared `kotoba-lang/langchain-store` machinery (ADR-2607141600)
  -- the seam ~190 cloud-itonami actors hand-roll as their own private
  `enc`/`dec*` two-liner; this store keeps only its domain wiring
  (job->tx/pull->job field shaping, the sequence counters, and the
  combined per-op transacts that must land atomically with a job
  update)."
  (:require [ofsup.registry :as registry]
            [langchain.db :as d]
            [langchain-store.core :as ls]))

(defprotocol Store
  (job [s id])
  (all-jobs [s])
  (ledger [s])
  (schedule-history [s] "the append-only service-schedule history (ofsup.registry drafts)")
  (supply-history [s] "the append-only supply-order history (ofsup.registry drafts)")
  (next-schedule-sequence [s jurisdiction] "next schedule-number sequence for a jurisdiction")
  (next-supply-sequence [s jurisdiction] "next supply-order-number sequence for a jurisdiction")
  (job-already-scheduled? [s job-id] "has this job's service operation already been scheduled?")
  (job-supply-already-open? [s job-id] "does this job already have an open supply-order coordination request?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-jobs [s jobs] "replace/seed the job directory (map id->job)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained job set covering both coordination
  lifecycles (schedule, supply order) plus the governor's own checks,
  so the actor + tests run offline."
  []
  {:jobs
   {"job-1" {:id "job-1" :client "Riverside Copy & Print"
              :document-type :photocopy :document-count 250 :turnaround-hours 4
              :requires-registration? false
              :registered? true :spec-basis "https://leginfo.legislature.ca.gov/faces/codes_displaySection.xhtml?sectionNum=6400.&lawCode=BPC"
              :legal-basis "California Business and Professions Code SS6400 et seq. (Legal Document Assistants Act)"
              :registration-confirmed? false
              :confidentiality-concern-raised? false :confidentiality-concern-resolved? false
              :scheduled? false :schedule-number nil
              :supply-coordination-open? false :supply-coordination-number nil
              :jurisdiction "USA" :status :intake}
    "job-2" {:id "job-2" :client "Riverside Copy & Print"
              :document-type :photocopy :document-count 40 :turnaround-hours 1
              :requires-registration? false
              :registered? false :spec-basis nil :legal-basis nil
              :registration-confirmed? false
              :confidentiality-concern-raised? false :confidentiality-concern-resolved? false
              :scheduled? false :schedule-number nil
              :supply-coordination-open? false :supply-coordination-number nil
              :jurisdiction "USA" :status :intake}
    "job-3" {:id "job-3" :client "Harborview Legal Support"
              :document-type :document-prep :document-count 12 :turnaround-hours 24
              :requires-registration? true
              :registered? true :spec-basis "https://leginfo.legislature.ca.gov/faces/codes_displaySection.xhtml?sectionNum=6400.&lawCode=BPC"
              :legal-basis "California Business and Professions Code SS6400 et seq. (Legal Document Assistants Act)"
              :registration-confirmed? false
              :confidentiality-concern-raised? false :confidentiality-concern-resolved? false
              :scheduled? false :schedule-number nil
              :supply-coordination-open? false :supply-coordination-number nil
              :jurisdiction "USA" :status :intake}
    "job-4" {:id "job-4" :client "Harborview Legal Support"
              :document-type :document-prep :document-count 8 :turnaround-hours 24
              :requires-registration? false
              :registered? true :spec-basis "https://leginfo.legislature.ca.gov/faces/codes_displaySection.xhtml?sectionNum=6400.&lawCode=BPC"
              :legal-basis "California Business and Professions Code SS6400 et seq. (Legal Document Assistants Act)"
              :registration-confirmed? false
              :confidentiality-concern-raised? true :confidentiality-concern-resolved? false
              :scheduled? false :schedule-number nil
              :supply-coordination-open? false :supply-coordination-number nil
              :jurisdiction "USA" :status :intake}
    "job-5" {:id "job-5" :client "Harborview Legal Support"
              :document-type :mailing :document-count 300 :turnaround-hours 8
              :requires-registration? false
              :registered? true :spec-basis "https://leginfo.legislature.ca.gov/faces/codes_displaySection.xhtml?sectionNum=6400.&lawCode=BPC"
              :legal-basis "California Business and Professions Code SS6400 et seq. (Legal Document Assistants Act)"
              :registration-confirmed? false
              :confidentiality-concern-raised? false :confidentiality-concern-resolved? false
              :scheduled? false :schedule-number nil
              ;; already has an open supply-order coordination request --
              ;; represents one opened through some earlier session/tool
              ;; (its own number is out of THIS store's own sequence
              ;; counter and history, deliberately: the flag alone is
              ;; what `already-coordinating-violations` checks, never a
              ;; history-vector length).
              :supply-coordination-open? true :supply-coordination-number nil
              :jurisdiction "USA" :status :intake}}})

;; ----------------------------- shared commit logic -----------------------------

(defn- schedule-service! [s job-id]
  (let [c (job s job-id)
        seq-n (next-schedule-sequence s (:jurisdiction c))
        result (registry/register-service-schedule job-id (:jurisdiction c) seq-n)]
    {:result result
     :job-patch {:scheduled? true
                 :schedule-number (get result "schedule_number")}}))

(defn- coordinate-supply! [s job-id]
  (let [c (job s job-id)
        seq-n (next-supply-sequence s (:jurisdiction c))
        result (registry/register-supply-order job-id (:jurisdiction c) seq-n)]
    {:result result
     :job-patch {:supply-coordination-open? true
                 :supply-coordination-number (get result "supply_order_number")}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (job [_ id] (get-in @a [:jobs id]))
  (all-jobs [_] (sort-by :id (vals (:jobs @a))))
  (ledger [_] (:ledger @a))
  (schedule-history [_] (:schedules @a))
  (supply-history [_] (:supplies @a))
  (next-schedule-sequence [_ jurisdiction] (get-in @a [:schedule-sequences jurisdiction] 0))
  (next-supply-sequence [_ jurisdiction] (get-in @a [:supply-sequences jurisdiction] 0))
  (job-already-scheduled? [_ job-id] (boolean (get-in @a [:jobs job-id :scheduled?])))
  (job-supply-already-open? [_ job-id] (boolean (get-in @a [:jobs job-id :supply-coordination-open?])))
  (commit-record! [s {:keys [action path value]}]
    (case action
      :job/log
      (let [job-id (first path)
            {:keys [patch spec-basis legal-basis]} value]
        (swap! a update-in [:jobs job-id]
               merge (assoc patch
                            :registered? (some? spec-basis)
                            :spec-basis spec-basis
                            :legal-basis legal-basis)))

      :job/flag-confidentiality-concern
      (let [job-id (first path)
            {:keys [note]} value]
        (swap! a update-in [:jobs job-id]
               merge {:confidentiality-concern-raised? true
                      :confidentiality-concern-resolved? false
                      :confidentiality-concern-note note}))

      :job/mark-scheduled
      (let [job-id (first path)
            {:keys [result job-patch]} (schedule-service! s job-id)
            jurisdiction (:jurisdiction (job s job-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:schedule-sequences jurisdiction] (fnil inc 0))
                       (update-in [:jobs job-id] merge job-patch)
                       (update :schedules registry/append result))))
        result)

      :job/mark-supply-coordinated
      (let [job-id (first path)
            {:keys [result job-patch]} (coordinate-supply! s job-id)
            jurisdiction (:jurisdiction (job s job-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:supply-sequences jurisdiction] (fnil inc 0))
                       (update-in [:jobs job-id] merge job-patch)
                       (update :supplies registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-jobs [s jobs] (when (seq jobs) (swap! a assoc :jobs jobs)) s))

(defn seed-db
  "A MemStore seeded with the demo job set. The deterministic default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :ledger [] :schedule-sequences {} :schedules []
                           :supply-sequences {} :supplies []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (ledger facts, schedule/supply records) are
  stored as EDN strings so `langchain.db` doesn't expand them into
  sub-entities -- the same convention every sibling actor's store
  uses. Built via `langchain-store.core/identity-schema` (every attr
  here is a plain `:db.unique/identity` mark)."
  (ls/identity-schema
   [:job/id :ledger/seq :schedule/seq :supply/seq
    :schedule-sequence/jurisdiction :supply-sequence/jurisdiction]))

(defn- job->tx [{:keys [id client document-type document-count turnaround-hours
                        requires-registration?
                        registered? spec-basis legal-basis
                        registration-confirmed?
                        confidentiality-concern-raised? confidentiality-concern-resolved? confidentiality-concern-note
                        scheduled? schedule-number
                        supply-coordination-open? supply-coordination-number
                        jurisdiction status]}]
  (cond-> {:job/id id}
    client                                        (assoc :job/client client)
    document-type                                    (assoc :job/document-type (ls/enc document-type))
    document-count                                     (assoc :job/document-count document-count)
    turnaround-hours                                     (assoc :job/turnaround-hours turnaround-hours)
    (some? requires-registration?)                         (assoc :job/requires-registration? requires-registration?)
    (some? registered?)                                      (assoc :job/registered? registered?)
    spec-basis                                                 (assoc :job/spec-basis spec-basis)
    legal-basis                                                  (assoc :job/legal-basis legal-basis)
    (some? registration-confirmed?)                                (assoc :job/registration-confirmed? registration-confirmed?)
    (some? confidentiality-concern-raised?)                          (assoc :job/confidentiality-concern-raised? confidentiality-concern-raised?)
    (some? confidentiality-concern-resolved?)                          (assoc :job/confidentiality-concern-resolved? confidentiality-concern-resolved?)
    confidentiality-concern-note                                         (assoc :job/confidentiality-concern-note confidentiality-concern-note)
    (some? scheduled?)                                                     (assoc :job/scheduled? scheduled?)
    schedule-number                                                          (assoc :job/schedule-number schedule-number)
    (some? supply-coordination-open?)                                          (assoc :job/supply-coordination-open? supply-coordination-open?)
    supply-coordination-number                                                   (assoc :job/supply-coordination-number supply-coordination-number)
    jurisdiction                                                                   (assoc :job/jurisdiction jurisdiction)
    status                                                                           (assoc :job/status status)))

(def ^:private job-pull
  [:job/id :job/client :job/document-type :job/document-count :job/turnaround-hours
   :job/requires-registration? :job/registered? :job/spec-basis :job/legal-basis
   :job/registration-confirmed?
   :job/confidentiality-concern-raised? :job/confidentiality-concern-resolved? :job/confidentiality-concern-note
   :job/scheduled? :job/schedule-number
   :job/supply-coordination-open? :job/supply-coordination-number
   :job/jurisdiction :job/status])

(defn- pull->job [m]
  (when (:job/id m)
    {:id (:job/id m) :client (:job/client m)
     :document-type (ls/dec* (:job/document-type m))
     :document-count (:job/document-count m) :turnaround-hours (:job/turnaround-hours m)
     :requires-registration? (boolean (:job/requires-registration? m))
     :registered? (boolean (:job/registered? m))
     :spec-basis (:job/spec-basis m) :legal-basis (:job/legal-basis m)
     :registration-confirmed? (boolean (:job/registration-confirmed? m))
     :confidentiality-concern-raised? (boolean (:job/confidentiality-concern-raised? m))
     :confidentiality-concern-resolved? (boolean (:job/confidentiality-concern-resolved? m))
     :confidentiality-concern-note (:job/confidentiality-concern-note m)
     :scheduled? (boolean (:job/scheduled? m)) :schedule-number (:job/schedule-number m)
     :supply-coordination-open? (boolean (:job/supply-coordination-open? m))
     :supply-coordination-number (:job/supply-coordination-number m)
     :jurisdiction (:job/jurisdiction m) :status (:job/status m)}))

(defrecord DatomicStore [conn]
  Store
  (job [_ id]
    (pull->job (d/pull (d/db conn) job-pull [:job/id id])))
  (all-jobs [_]
    (->> (d/q '[:find [?id ...] :where [?e :job/id ?id]] (d/db conn))
         (map #(pull->job (d/pull (d/db conn) job-pull [:job/id %])))
         (sort-by :id)))
  (ledger [_]
    (ls/read-stream conn :ledger/seq :ledger/fact))
  (schedule-history [_]
    (ls/read-stream conn :schedule/seq :schedule/record))
  (supply-history [_]
    (ls/read-stream conn :supply/seq :supply/record))
  (next-schedule-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :schedule-sequence/jurisdiction ?j] [?e :schedule-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (next-supply-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :supply-sequence/jurisdiction ?j] [?e :supply-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (job-already-scheduled? [s job-id]
    (boolean (:scheduled? (job s job-id))))
  (job-supply-already-open? [s job-id]
    (boolean (:supply-coordination-open? (job s job-id))))
  (commit-record! [s {:keys [action path value]}]
    (case action
      :job/log
      (let [job-id (first path)
            {:keys [patch spec-basis legal-basis]} value]
        (d/transact! conn [(job->tx (assoc (merge (job s job-id) patch)
                                           :id job-id
                                           :registered? (some? spec-basis)
                                           :spec-basis spec-basis
                                           :legal-basis legal-basis))]))

      :job/flag-confidentiality-concern
      (let [job-id (first path)
            {:keys [note]} value]
        (d/transact! conn [(job->tx (assoc (job s job-id)
                                           :id job-id
                                           :confidentiality-concern-raised? true
                                           :confidentiality-concern-resolved? false
                                           :confidentiality-concern-note note))]))

      :job/mark-scheduled
      (let [job-id (first path)
            {:keys [result job-patch]} (schedule-service! s job-id)
            jurisdiction (:jurisdiction (job s job-id))
            next-n (inc (next-schedule-sequence s jurisdiction))]
        (d/transact! conn
                     [(job->tx (assoc (merge (job s job-id) job-patch) :id job-id))
                      {:schedule-sequence/jurisdiction jurisdiction :schedule-sequence/next next-n}
                      {:schedule/seq (count (schedule-history s)) :schedule/record (ls/enc (get result "record"))}])
        result)

      :job/mark-supply-coordinated
      (let [job-id (first path)
            {:keys [result job-patch]} (coordinate-supply! s job-id)
            jurisdiction (:jurisdiction (job s job-id))
            next-n (inc (next-supply-sequence s jurisdiction))]
        (d/transact! conn
                     [(job->tx (assoc (merge (job s job-id) job-patch) :id job-id))
                      {:supply-sequence/jurisdiction jurisdiction :supply-sequence/next next-n}
                      {:supply/seq (count (supply-history s)) :supply/record (ls/enc (get result "record"))}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    (ls/append-blob! conn :ledger/seq :ledger/fact (count (ledger s)) fact))
  (with-jobs [s jobs]
    (when (seq jobs) (d/transact! conn (mapv job->tx (vals jobs)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:jobs ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [jobs]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (with-jobs s jobs))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo job set -- the Datomic-backed
  analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
