(ns ofsup.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for this repo: it previously had a
  product face (`docs/index.html`) but NO operator console and no
  generator at all. This namespace drives the REAL actor stack --
  `ofsup.operation/build` (the compiled langgraph-clj StateGraph) ->
  `ofsup.ofsupllm` (the contained advisor) -> `ofsup.governor` (the
  independent censor) -> `ofsup.phase` (the rollout gate) ->
  `ofsup.store` (the SSoT + append-only ledger) -- through a scenario
  built out of this repo's OWN seeded jobs (`ofsup.store/demo-data`:
  `job-1`..`job-5`, no invented ids) and renders whatever the run
  actually produced.

  Nothing on the page is hand-typed domain content:
    - the job table is `store/all-jobs` AFTER the run,
    - the hold tables are the governor's own `:violations` maps,
    - the ledger table is `store/ledger`,
    - the draft-record tables are `ofsup.registry` output via the store,
    - the gate table is read out of `ofsup.phase/phases`,
      `ofsup.governor/allowed-ops` / `allowed-actions` / `high-stakes`
      / `confidence-floor` / `high-cost-supply-threshold-usd`,
    - the jurisdiction table is `ofsup.facts/catalog` + `facts/coverage`.

  TWO KINDS OF HOLD, RENDERED IN TWO TABLES
  =========================================
  A `:hold` disposition reaches the ledger as `:t :governor-hold`
  whether the GOVERNOR refused (non-empty `:violations` -- a real HARD
  compliance stop) or the ROLLOUT PHASE GATE stopped an otherwise-clean
  proposal (`:violations []` plus `:phase-reason`, see
  `ofsup.phase/gate`). Counting `:t :governor-hold` alone therefore
  over-reports governor refusals. This renderer classifies on
  `(seq :violations)` and renders the two in SEPARATE tables, and
  `-main`'s build-time invariant counts only the first kind.

  BUILD-TIME INVARIANT
  ====================
  `-main` REFUSES to write the file unless the run produced at least
  one real HARD governor hold in BOTH the live graph states and the
  durable store ledger, and at least one committed record. A console
  that shows only happy paths would not evidence that the governor can
  actually say no, and a renderer that silently emitted one would be
  the `measured-nothing-looks-like-measured-clean` failure mode.

  Deterministic: no timestamps, no wall-clock, no map-iteration-order
  dependence (every row picks explicit keys, every set is sorted), so
  two runs against the same seed are byte-identical.

  Usage: `clojure -M:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [jp-go-dds.skin]
            [langgraph.graph :as g]
            [ofsup.facts :as facts]
            [ofsup.governor :as governor]
            [ofsup.operation :as op]
            [ofsup.phase :as phase]
            [ofsup.store :as store]))

;; ============================== the real run ==============================

(def ^:private operator
  {:actor-id "op-1" :actor-role :office-support-operations-coordinator})

(defn- ctx [ph] (assoc operator :phase ph))

(defn run-demo!
  "Drives a freshly seeded store through every disposition this actor
  can reach, using ONLY the seeded job ids `job-1`..`job-5`.

  Committed / approved paths
    01 job-1 record logged                     -> phase-3 auto-commit
    03 job-1 service operation scheduled       -> phase-3 auto-commit
    04 job-1 low-cost supply order coordinated -> phase-3 auto-commit
    07 job-3 HIGH-cost supply order            -> governor escalate
                                                  (`:office-support/
                                                  high-cost-supply`)
                                                  -> human approves
    08 job-1 confidentiality concern flagged   -> ALWAYS escalates
                                                  -> human approves
    17 job-2 record logged AT PHASE 2          -> phase escalate
                                                  (`:phase-approval`)
                                                  -> human approves

  Rollout-phase hold (governor CLEAN, `:violations []`)
    02 job-1 supply order AT PHASE 1           -> `:phase-disabled`
       -- the SAME request that auto-commits at step 04. The only
       difference is the phase, which is exactly why this row must not
       be counted as a governor refusal.

  Human rejection (not a governor refusal either)
    09 job-5 confidentiality concern flagged   -> escalates, human
                                                  REJECTS

  HARD governor holds -- un-overridable, never reach a human
    05 job-1 schedule again      -> :already-scheduled
    06 job-1 supply order again  -> :already-coordinating
    10 job-2 record, jurisdiction off-catalog -> :no-spec-basis
    11 job-2 schedule            -> :record-not-verified
    12 job-3 schedule            -> :registration-unconfirmed
    13 job-4 schedule            -> :open-confidentiality-concern
    14 job-5 supply order        -> :already-coordinating (seeded open)
    15 job-2 supply order        -> :record-not-verified (supply path)
    16 job-4 supply order        -> :open-confidentiality-concern (ditto)

  Step order is load-bearing: the double-actuation holds (05/06) run
  BEFORE the confidentiality concern is flagged on `job-1` (08), so
  each HARD hold carries exactly ONE violation and is attributable to
  one rule. Returns `{:db .. :steps [..]}` -- `:state` on each step is
  the graph's own final state, not a summary."
  []
  (let [db    (store/seed-db)
        actor (op/build db)
        steps (atom [])
        step!
        (fn [{:keys [id note phase request approve]}]
          (let [ph  (or phase phase/default-phase)
                r1  (g/run* actor {:request request :context (ctx ph)} {:thread-id id})
                paused? (= :interrupted (:status r1))
                r2  (when (and approve paused?)
                      (g/run* actor {:approval approve} {:thread-id id :resume? true}))
                fin (or r2 r1)]
            (swap! steps conj
                   {:id id :note note :phase ph
                    :op (:op request) :subject (:subject request)
                    :request request
                    :approval (when r2 approve)
                    :approval-requested? paused?
                    :status (:status fin)
                    :state (:state fin)})
            fin))]

    ;; --- job-1: a clean job walks the whole lifecycle ---------------------
    (step! {:id "s01" :note "clean client/job record logged (auto)"
            :request {:op :log-service-record :subject "job-1"
                      :patch {:id "job-1" :client "Riverside Copy & Print"}}})

    ;; same request as s04, one phase earlier -- phase gate, NOT the governor
    (step! {:id "s02" :phase 1
            :note "identical supply-order request one phase earlier"
            :request {:op :coordinate-supply-order :subject "job-1"
                      :estimated-cost-usd 120}})

    (step! {:id "s03" :note "service operation scheduled (auto)"
            :request {:op :schedule-service-operation :subject "job-1"}})

    (step! {:id "s04" :note "routine consumables reorder (auto)"
            :request {:op :coordinate-supply-order :subject "job-1"
                      :estimated-cost-usd 120}})

    (step! {:id "s05" :note "double-schedule attempt"
            :request {:op :schedule-service-operation :subject "job-1"}})

    (step! {:id "s06" :note "second supply-order attempt"
            :request {:op :coordinate-supply-order :subject "job-1"
                      :estimated-cost-usd 120}})

    ;; --- escalations that a human resolves --------------------------------
    (step! {:id "s07" :note "high-cost equipment order -- always escalates"
            :request {:op :coordinate-supply-order :subject "job-3"
                      :estimated-cost-usd 900}
            :approve {:status :approved :by "op-1"}})

    (step! {:id "s08" :note "confidentiality concern -- never auto at any phase"
            :request {:op :flag-confidentiality-concern :subject "job-1"
                      :note "customer reported an unattended printout of a financial statement"}
            :approve {:status :approved :by "op-1"}})

    (step! {:id "s09" :note "confidentiality concern -- human declines"
            :request {:op :flag-confidentiality-concern :subject "job-5"
                      :note "duplicate report of the same mailing batch"}
            :approve {:status :rejected :by "op-1"}})

    ;; --- HARD governor holds ---------------------------------------------
    (step! {:id "s10" :note "jurisdiction absent from ofsup.facts/catalog"
            :request {:op :log-service-record :subject "job-2"
                      :patch {:id "job-2" :client "Riverside Copy & Print"}
                      :no-spec? true}})

    (step! {:id "s11" :note "schedule on an unregistered record"
            :request {:op :schedule-service-operation :subject "job-2"}})

    (step! {:id "s12" :note "schedule needing an unconfirmed document-preparer registration"
            :request {:op :schedule-service-operation :subject "job-3"}})

    (step! {:id "s13" :note "schedule against an open confidentiality concern"
            :request {:op :schedule-service-operation :subject "job-4"}})

    (step! {:id "s14" :note "supply order on a job whose coordination is already open"
            :request {:op :coordinate-supply-order :subject "job-5"
                      :estimated-cost-usd 120}})

    (step! {:id "s15" :note "supply order on an unregistered record"
            :request {:op :coordinate-supply-order :subject "job-2"
                      :estimated-cost-usd 120}})

    (step! {:id "s16" :note "supply order against an open confidentiality concern"
            :request {:op :coordinate-supply-order :subject "job-4"
                      :estimated-cost-usd 120}})

    ;; --- phase-2 supervision: clean, but no op is auto-eligible ----------
    (step! {:id "s17" :phase 2
            :note "record logged under phase-2 supervision"
            :request {:op :log-service-record :subject "job-2"
                      :patch {:id "job-2" :client "Riverside Copy & Print"}}
            :approve {:status :approved :by "op-1"}})

    {:db db :steps @steps}))

;; ========================== outcome classification ==========================

(defn- hold-fact
  "The decision fact this step's graph run wrote for a hold, if any."
  [state]
  (last (filter #(#{:governor-hold :approval-rejected} (:t %)) (:audit state))))

(defn outcome
  "Classify one step from its own final graph state. `:governor-hard-hold`
  is the ONLY kind that means the governor refused: a `:phase-hold`
  carries `:violations []` and would otherwise be mistaken for one."
  [{:keys [state approval]}]
  (let [d  (:disposition state)
        hf (hold-fact state)
        vs (vec (:violations hf))]
    (cond
      (and (= :commit d) approval)
      {:kind :approved-commit :label "approved &amp; committed" :css "ok"}

      (= :commit d)
      {:kind :auto-commit :label "auto-committed" :css "ok"}

      (= :approval-rejected (:t hf))
      {:kind :human-rejected :label "human declined" :css "warn"
       :rules (mapv :rule vs)}

      (and (= :hold d) (seq vs))
      {:kind :governor-hard-hold :css "critical"
       :label (str "HARD hold &middot; " (str/join ", " (map (comp name :rule) vs)))
       :rules (mapv :rule vs) :violations vs}

      (= :hold d)
      {:kind :phase-hold :css "warn"
       :label (str "phase gate &middot; " (name (or (:phase-reason hf) :unknown)))
       :reason (:phase-reason hf)}

      (= :escalate d)
      {:kind :awaiting-approval :label "awaiting human approval" :css "warn"}

      :else {:kind :unknown :label "unknown" :css "muted"})))

;; ====================== approver attribution (measured) ======================

(def ^:private approver-key-re
  "Any key whose NAME mentions approval. Deliberately a scan rather than
  a fixed key, so this page self-corrects the day the store starts
  retaining the approver under whatever key it chooses."
  #"(?i)approv")

(defn- kname [k] (if (keyword? k) (name k) (str k)))

(defn- approver-hits
  "Scan `ms` (a coll of maps) for approver-attribution keys."
  [where ms]
  (vec (for [m ms
             [k v] (seq m)
             :when (and (some? v) (re-find approver-key-re (kname k)))]
         {:where where :key (kname k) :value v
          :entity (or (:id m) (:subject m) (get m "record_id") (:job-id m))})))

(defn approver-audit
  "MEASURES, rather than assumes, whether an approver survives into the
  committed record. Three surfaces:
    :actor-record -- the `:record` the graph's own `:request-approval`
                     node emitted (`:payload`),
    :store-jobs   -- the SSoT rows after the run,
    :store-ledger -- the append-only decision facts.
  A hit on the first but not the last two means the actor attached the
  approver and the STORE dropped it."
  [db steps]
  (let [payloads (keep #(get-in % [:state :record :payload]) steps)
        records  (keep #(get-in % [:state :record]) steps)
        a (into (approver-hits :actor-record payloads)
                (approver-hits :actor-record records))
        b (approver-hits :store-jobs (store/all-jobs db))
        c (approver-hits :store-ledger (store/ledger db))]
    {:actor-record a :store-jobs b :store-ledger c
     :retained? (boolean (or (seq b) (seq c)))}))

(defn approval-decisions
  "One row per step that actually reached the human approval node.
  Keyed by the step id, NOT joined on [op subject] -- that pair is not
  unique across a run and a join on it can make one record inherit an
  earlier step's approver."
  [steps]
  (vec (for [{:keys [id note op subject approval approval-requested? state] :as s} steps
             :when approval-requested?]
         (let [o (outcome s)]
           {:id id :note note :op op :subject subject
            :decision (if approval (:status approval) :not-resolved)
            :by (:by approval)
            :payload-approver (get-in state [:record :payload :approved-by])
            :outcome (:kind o)}))))

;; ================================ rendering ================================

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw [v] (if (keyword? v) (name v) (str v)))

(defn- yn [b css-true css-false]
  (if b
    (str "<span class=\"" css-true "\">yes</span>")
    (str "<span class=\"" css-false "\">no</span>")))

(defn- row [& cells]
  (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- table [headers rows]
  (str "    <table>\n"
       "      <thead><tr>"
       (str/join (map #(str "<th>" % "</th>") headers))
       "</tr></thead>\n"
       "      <tbody>\n"
       (if (seq rows) (str (str/join "\n" rows) "\n") "")
       "      </tbody>\n"
       "    </table>\n"))

(defn- section [title lead body]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       (if lead (str "    <p class=\"muted\">" lead "</p>\n") "")
       body
       "  </section>\n"))

;; --- job directory -------------------------------------------------------

(defn- job-row [{:keys [id client document-type document-count turnaround-hours
                        jurisdiction registered? requires-registration?
                        registration-confirmed?
                        confidentiality-concern-raised?
                        confidentiality-concern-resolved?
                        scheduled? schedule-number
                        supply-coordination-open? supply-coordination-number]}]
  (row (str "<code>" (esc id) "</code>")
       (esc client)
       (str (esc (kw document-type)) " &middot; " (esc document-count)
            " docs / " (esc turnaround-hours) "h")
       (esc jurisdiction)
       (yn registered? "ok" "critical")
       (if requires-registration?
         (yn registration-confirmed? "ok" "critical")
         "<span class=\"muted\">n/a</span>")
       (cond
         (and confidentiality-concern-raised? (not confidentiality-concern-resolved?))
         "<span class=\"critical\">open</span>"
         confidentiality-concern-raised? "<span class=\"ok\">resolved</span>"
         :else "<span class=\"muted\">none</span>")
       (if scheduled?
         (str "<span class=\"ok\">" (esc (or schedule-number "-")) "</span>")
         "<span class=\"muted\">not scheduled</span>")
       (if supply-coordination-open?
         (str "<span class=\"warn\">" (esc (or supply-coordination-number "open (pre-existing)")) "</span>")
         "<span class=\"muted\">none</span>")))

;; --- scenario ------------------------------------------------------------

(defn- step-row [{:keys [id note op subject phase] :as s}]
  (let [o (outcome s)]
    (row (str "<code>" (esc id) "</code>")
         (str "<code>" (esc (kw op)) "</code>")
         (str "<code>" (esc subject) "</code>")
         (esc phase)
         (esc note)
         (str "<span class=\"" (:css o) "\">" (:label o) "</span>"))))

(defn- hard-hold-rows [steps]
  (vec (for [s steps
             :let [o (outcome s)]
             :when (= :governor-hard-hold (:kind o))
             v (:violations o)]
         (row (str "<code>" (esc (:id s)) "</code>")
              (str "<code>" (esc (kw (:op s))) "</code>")
              (str "<code>" (esc (:subject s)) "</code>")
              (str "<span class=\"critical\">" (esc (kw (:rule v))) "</span>")
              (esc (:detail v))))))

(defn- phase-hold-rows [steps]
  (vec (for [s steps
             :let [o (outcome s)]
             :when (= :phase-hold (:kind o))]
         (row (str "<code>" (esc (:id s)) "</code>")
              (str "<code>" (esc (kw (:op s))) "</code>")
              (str "<code>" (esc (:subject s)) "</code>")
              (esc (:phase s))
              (str "<span class=\"warn\">" (esc (kw (:reason o))) "</span>")
              "<span class=\"muted\">[] — the governor raised no violation</span>"))))

;; --- ledger --------------------------------------------------------------

(defn- ledger-row [{:keys [t op subject disposition basis violations phase-reason]}]
  (row (str "<code>" (esc (kw t)) "</code>")
       (str "<code>" (esc (kw (or op :n-a))) "</code>")
       (str "<code>" (esc subject) "</code>")
       (esc (kw (or disposition "")))
       (cond
         (seq basis) (str "<span class=\"critical\">"
                          (esc (str/join ", " (map kw basis))) "</span>")
         phase-reason (str "<span class=\"warn\">phase: " (esc (kw phase-reason)) "</span>")
         :else "<span class=\"muted\">—</span>")
       (if (seq violations)
         "<span class=\"critical\">governor refused</span>"
         (if (= :governor-hold t)
           "<span class=\"warn\">rollout gate</span>"
           "<span class=\"muted\">—</span>"))))

;; --- gate contract (read out of the namespaces, not hand-typed) ----------

(defn- phase-rows []
  (vec (for [[p {:keys [label writes auto]}] (sort-by key phase/phases)]
         (row (esc p)
              (esc label)
              (if (seq writes)
                (str/join ", " (map #(str "<code>" (esc (kw %)) "</code>")
                                    (sort (map kw writes))))
                "<span class=\"muted\">none</span>")
              (if (seq auto)
                (str/join ", " (map #(str "<code>" (esc (kw %)) "</code>")
                                    (sort (map kw auto))))
                "<span class=\"muted\">none — every write needs a human</span>")))))

(defn- op-rows []
  (let [auto3 (get-in phase/phases [3 :auto])]
    (vec (for [o (sort (map kw governor/allowed-ops))
               :let [o-kw (keyword o)]]
           (row (str "<code>:" (esc o) "</code>")
                ;; NB: an explicit "no" branch. A nil here would render as an
                ;; EMPTY cell, which reads as "not determined" when the answer
                ;; is a definite no -- the same silence-looks-like-clean shape
                ;; the two hold tables above exist to avoid.
                (if (contains? auto3 o-kw)
                  "<span class=\"ok\">phase-3 auto-commit when the governor is clean</span>"
                  "<span class=\"critical\">never — not in any phase&rsquo;s :auto set</span>")
                (cond
                  (= :flag-confidentiality-concern o-kw)
                  "<span class=\"warn\">ALWAYS human &middot; never in any phase&rsquo;s :auto set &middot; unconditional high-stakes</span>"
                  (= :coordinate-supply-order o-kw)
                  (str "<span class=\"warn\">human once the estimate exceeds "
                       governor/high-cost-supply-threshold-usd " USD</span>")
                  :else
                  (str "<span class=\"muted\">human below the "
                       governor/confidence-floor " confidence floor</span>")))))))

;; --- approver attribution -----------------------------------------------

(defn- approval-rows [rows]
  (vec (for [{:keys [id op subject decision by payload-approver outcome]} rows]
         (row (str "<code>" (esc id) "</code>")
              (str "<code>" (esc (kw op)) "</code>")
              (str "<code>" (esc subject) "</code>")
              (if (= :approved decision)
                "<span class=\"ok\">approved</span>"
                (str "<span class=\"warn\">" (esc (kw decision)) "</span>"))
              (if by (str "<code>" (esc by) "</code>")
                  "<span class=\"muted\">—</span>")
              (if payload-approver
                (str "<code>" (esc payload-approver) "</code>")
                "<span class=\"muted\">—</span>")
              (esc (kw outcome))))))

(defn- attribution-rows [{:keys [actor-record store-jobs store-ledger]}]
  (vec (for [[label hits] [["actor-emitted commit record (<code>:record</code>)" actor-record]
                           ["store SSoT rows (<code>store/all-jobs</code>)" store-jobs]
                           ["append-only ledger (<code>store/ledger</code>)" store-ledger]]]
         (row label
              (if (seq hits)
                (str "<span class=\"ok\">" (count hits) "</span>")
                "<span class=\"critical\">0</span>")
              (if (seq hits)
                (str/join ", " (sort (distinct (map #(str "<code>" (esc (:key %)) "</code>") hits))))
                "<span class=\"muted\">no approver key present</span>")))))

;; --- draft records / jurisdictions --------------------------------------

(defn- draft-row [m]
  (row (str "<code>" (esc (get m "record_id")) "</code>")
       (esc (get m "kind"))
       (str "<code>" (esc (get m "job_id")) "</code>")
       (esc (get m "jurisdiction"))
       (if (get m "immutable")
         "<span class=\"ok\">immutable</span>"
         "<span class=\"warn\">mutable</span>")
       "<span class=\"warn\">draft-unsigned</span>"))

(defn- jurisdiction-rows []
  (vec (for [[iso3 {:keys [name owner-authority legal-basis provenance required-evidence]}]
             (sort-by key facts/catalog)]
         (row (str "<code>" (esc iso3) "</code>")
              (esc name)
              (esc owner-authority)
              (esc legal-basis)
              (str "<a href=\"" (esc provenance) "\">" (esc provenance) "</a>")
              (esc (count required-evidence))))))

;; ================================= page =================================

(defn render
  "Renders the console from a completed run. Every table below is derived
  from `db`/`steps`; nothing is hand-written domain content."
  [{:keys [db steps]}]
  (let [ledger    (vec (store/ledger db))
        jobs      (store/all-jobs db)
        outs      (mapv outcome steps)
        hard      (filterv #(= :governor-hard-hold %) (map :kind outs))
        approvals (approval-decisions steps)
        attrib    (approver-audit db steps)
        cov       (facts/coverage)]
    (str
     "<!DOCTYPE html>\n"
     "<html lang=\"en\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">"
     "<meta name=\"color-scheme\" content=\"light\">"
     "<title>cloud-itonami-isic-8219 &middot; office support services &middot; operator console</title>"
     "<style>" (jp-go-dds.skin/dds+skin) "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Office support services (ISIC 8219) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample &middot; governor-gated &middot; generated at build time by <code>ofsup.render-html</code> from a real actor run</span>\n"
     "</header>\n"
     "<main>\n"

     (section
      "Scenario dispositions"
      (str "Every row is one <code>langgraph.graph/run*</code> of the compiled "
           "<code>ofsup.operation</code> StateGraph against the seeded jobs "
           "<code>job-1</code>&ndash;<code>job-5</code>. "
           (count steps) " runs: "
           (count (filter #{:auto-commit} (map :kind outs))) " auto-committed, "
           (count (filter #{:approved-commit} (map :kind outs))) " approved by a human, "
           (count hard) " refused by the governor, "
           (count (filter #{:phase-hold} (map :kind outs))) " stopped by the rollout phase gate, "
           (count (filter #{:human-rejected} (map :kind outs))) " declined by a human.")
      (table ["Run" "Op" "Job" "Phase" "What it exercises" "Disposition"]
             (mapv step-row steps)))

     (section
      "HARD governor holds — the governor refused"
      (str "These are <code>ofsup.governor</code>&rsquo;s own violation maps, verbatim. "
           "A HARD hold is un-overridable: it never reaches the human approval node, so "
           "no operator can sign it off. Rules exercised here: "
           (str/join ", "
                     (sort (distinct (for [s steps
                                           :let [o (outcome s)]
                                           :when (= :governor-hard-hold (:kind o))
                                           r (:rules o)]
                                       (str "<code>" (kw r) "</code>"))))) ".")
      (table ["Run" "Op" "Job" "Rule" "Governor detail"] (hard-hold-rows steps)))

     (section
      "Rollout-phase holds — the governor was CLEAN"
      (str "Distinct from the table above and deliberately kept apart. "
           "<code>ofsup.phase/gate</code> can stop an otherwise-clean proposal because the "
           "op is not yet enabled for that phase; the resulting fact still lands in the "
           "ledger as <code>:t :governor-hold</code>, but with <code>:violations []</code>. "
           "Counting <code>:t :governor-hold</code> alone would report these as compliance "
           "refusals, which they are not. Run <code>s02</code> is the same request as "
           "<code>s04</code> &mdash; only the phase differs.")
      (table ["Run" "Op" "Job" "Phase" "Phase reason" "Governor violations"]
             (phase-hold-rows steps)))

     (section
      "Human approval decisions &amp; approver attribution"
      (str "The graph pauses at <code>:request-approval</code> (<code>interrupt-before</code>) "
           "and a human operator resumes it. "
           (if (:retained? attrib)
             "The approver IS retained on the committed record."
             (str "<strong>Approver attribution is audit only &mdash; not retained on record.</strong> "
                  "The <code>:request-approval</code> node does attach <code>:approved-by</code> to the "
                  "record&rsquo;s <code>:payload</code>, but <code>ofsup.store/commit-record!</code> "
                  "destructures only <code>:value</code>, and the committed <code>:t :committed</code> "
                  "ledger fact carries no approver field, so the identity survives only in the live "
                  "graph run. This section is derived at render time by scanning all three surfaces for "
                  "approver keys, so it corrects itself the day the store retains one."))
           " Rows are keyed by run id, never joined on <code>[op job]</code> &mdash; that pair is not "
           "unique across a run.")
      (str
       (table ["Run" "Op" "Job" "Decision" "Approver (live run)" "On <code>:record :payload</code>" "Outcome"]
              (approval-rows approvals))
       "    <p class=\"muted\">Where an approver key survives:</p>\n"
       (table ["Surface" "Approver keys found" "Keys"] (attribution-rows attrib))))

     (section
      "Job directory (SSoT after the run)"
      "Read back out of <code>ofsup.store/all-jobs</code> once the scenario has finished."
      (table ["Job" "Client" "Work" "Jurisdiction" "Record verified"
              "Preparer registration" "Confidentiality concern"
              "Service schedule" "Supply coordination"]
             (mapv job-row jobs)))

     (section
      "Append-only audit ledger (this run)"
      (str (count ledger) " immutable decision facts &mdash; <code>ofsup.store/ledger</code>. "
           "The last column separates a compliance refusal from a rollout-gate stop; both "
           "are stored under the same <code>:t</code>.")
      (table ["Fact" "Op" "Job" "Disposition" "Basis" "Kind"]
             (mapv ledger-row ledger)))

     (section
      "Op gate contract"
      (str "Read out of <code>ofsup.governor/allowed-ops</code> and "
           "<code>ofsup.phase/phases</code> at build time. The action allowlist is closed: "
           (str/join ", " (map #(str "<code>" (esc %) "</code>")
                               (sort (map #(str ":" (kw %)) governor/allowed-actions))))
           " &mdash; finalizing a data-privacy-compliance determination or releasing a "
           "client&rsquo;s documents cannot be represented in it at all.")
      (str
       (table ["Op" "Phase-3 auto?" "Human sign-off"] (op-rows))
       "    <p class=\"muted\">Rollout phases:</p>\n"
       (table ["Phase" "Label" "Writes enabled" "Auto-commit eligible"] (phase-rows))))

     (section
      "Draft service-schedule records"
      "Pure <code>ofsup.registry</code> output, kept by the store. A coordination draft — never a real document release."
      (table ["Record" "Kind" "Job" "Jurisdiction" "Immutability" "Certificate"]
             (mapv draft-row (store/schedule-history db))))

     (section
      "Draft supply-order records"
      "Pure <code>ofsup.registry</code> output. A procurement coordination note — never a purchase commitment."
      (table ["Record" "Kind" "Job" "Jurisdiction" "Immutability" "Certificate"]
             (mapv draft-row (store/supply-history db))))

     (section
      "Jurisdiction spec-basis catalog"
      (str "<code>ofsup.facts/catalog</code> — " (:covered cov) " of "
           (:requested cov) " jurisdictions carry an official, citable source. "
           "A jurisdiction absent from this table has NO spec-basis, and the governor holds "
           "any <code>:log-service-record</code> proposal that tries to register a record on "
           "one (run <code>s10</code> above).")
      (table ["ISO3" "Jurisdiction" "Owner authority" "Legal basis" "Provenance" "Required evidence"]
             (jurisdiction-rows)))

     "</main>\n"
     "<footer>\n"
     "  <p>Generated by <code>clojure -M:render-html</code> (<code>ofsup.render-html</code>) from a real "
     "<code>ofsup.operation</code> run over <code>ofsup.store/demo-data</code>. No hand-written rows, no timestamps — "
     "re-running against the same seed reproduces this file byte for byte.</p>\n"
     "  <p>cloud-itonami-isic-8219 &middot; AGPL-3.0-or-later</p>\n"
     "</footer>\n"
     "</body></html>\n")))

;; ================================= main =================================

(defn- hard-hold-count [steps]
  (count (filter #(= :governor-hard-hold (:kind (outcome %))) steps)))

(defn- ledger-hard-hold-count [db]
  (count (filter #(and (= :governor-hold (:t %)) (seq (:violations %)))
                 (store/ledger db))))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db steps] :as run} (run-demo!)
        live   (hard-hold-count steps)
        durable (ledger-hard-hold-count db)
        commits (count (filter #(= :committed (:t %)) (store/ledger db)))
        rules  (sort (distinct (for [s steps
                                     :let [o (outcome s)]
                                     :when (= :governor-hard-hold (:kind o))
                                     r (:rules o)]
                                 r)))]
    ;; Build-time invariant: a console that cannot show the governor
    ;; refusing is not evidence of a governor. Refuse to write, do not
    ;; write a quietly-green page.
    (when (or (zero? live) (zero? durable))
      (throw (ex-info (str "refusing to write " out
                           ": the run produced no HARD governor hold"
                           " (live=" live " durable=" durable ")."
                           " A hold with empty :violations is a rollout-phase gate,"
                           " not a governor refusal, and does not satisfy this invariant.")
                      {:live-hard-holds live :durable-hard-holds durable
                       :steps (count steps)})))
    (when (zero? commits)
      (throw (ex-info (str "refusing to write " out
                           ": the run committed nothing, so the console would show"
                           " only refusals and could not evidence the approved paths.")
                      {:committed commits})))
    (spit out (render run))
    (println "wrote" out)
    (println "  steps:              " (count steps))
    (println "  HARD governor holds:" live "(durable in ledger:" (str durable ")")
             "rules:" (str/join ", " (map name rules)))
    (println "  phase-gate holds:   "
             (count (filter #(= :phase-hold (:kind (outcome %))) steps)))
    (println "  human rejections:   "
             (count (filter #(= :human-rejected (:kind (outcome %))) steps)))
    (println "  ledger facts:       " (count (store/ledger db))
             "| committed:" commits)
    (println "  approver retained on record?"
             (:retained? (approver-audit db steps)))))
