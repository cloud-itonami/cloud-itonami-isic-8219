(ns ofsup.registry
  "Pure-function service-schedule + supply-order record construction --
  an append-only office-support-operations coordination draft
  book-of-record.

  Like every sibling actor's registry, there is no single
  international reference-number standard for a service-schedule or a
  supply-order coordination record -- every copy shop/document-
  preparation operator/jurisdiction assigns its own reference format.
  This namespace does NOT invent one; it builds a jurisdiction-scoped
  sequence number and validates the record's required fields, the same
  honest, non-fabricating discipline `ofsup.facts` uses.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real job-management, procurement, or document-release
  system. It builds the COORDINATION RECORD this actor would keep, not
  a real document release or a real purchase commitment -- both of
  those remain a certified office-support operator's own act, entirely
  outside this actor's closed op allowlist (see `ofsup.governor` ns
  docstring `SCOPE`)."
  (:require [clojure.string :as str]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is
  the certified operator's act, not this actor's."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn register-service-schedule
  "Validate + construct the SERVICE-SCHEDULE registration DRAFT -- a
  job/equipment scheduling COORDINATION note, never a real document
  release/actuation. Pure function -- does not touch any real job-
  management/equipment-dispatch system; it builds the RECORD this
  actor would keep. `ofsup.governor` independently re-verifies the
  job's own registration/registration-confirmation/confidentiality-
  concern ground truth, and blocks a double-schedule of the same job,
  before this is ever allowed to commit."
  [job-id jurisdiction sequence]
  (when-not (and job-id (not= job-id ""))
    (throw (ex-info "service-schedule: job_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "service-schedule: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "service-schedule: sequence must be >= 0" {})))
  (let [schedule-number (str (str/upper-case jurisdiction) "-SVC-" (zero-pad sequence 6))
        record {"record_id" schedule-number
                "kind" "service-schedule-draft"
                "job_id" job-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "schedule_number" schedule-number
     "certificate" (unsigned-certificate "ServiceSchedule" schedule-number schedule-number)}))

(defn register-supply-order
  "Validate + construct the SUPPLY-ORDER registration DRAFT -- a
  paper/toner/equipment procurement coordination note, never a real
  purchase order or shipment release. Pure function -- does not touch
  any real procurement/inventory system; it builds the RECORD this
  actor would keep. `ofsup.governor` independently re-verifies the
  job's own registration/confidentiality-concern ground truth, and
  blocks opening a second supply-order request while one is already
  open, before this is ever allowed to commit."
  [job-id jurisdiction sequence]
  (when-not (and job-id (not= job-id ""))
    (throw (ex-info "supply-order: job_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "supply-order: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "supply-order: sequence must be >= 0" {})))
  (let [supply-number (str (str/upper-case jurisdiction) "-SUP-" (zero-pad sequence 6))
        record {"record_id" supply-number
                "kind" "supply-order-draft"
                "job_id" job-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "supply_order_number" supply-number
     "certificate" (unsigned-certificate "SupplyOrder" supply-number supply-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
