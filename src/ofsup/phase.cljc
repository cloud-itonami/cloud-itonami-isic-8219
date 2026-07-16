(ns ofsup.phase
  "Phase 0->3 staged rollout for the office-support-services
  (photocopying / document preparation / mailing) operations-
  coordination actor.

    Phase 0  read-only          -- no writes, still governor-gated.
    Phase 1  assisted-logging   -- client/job record logging allowed,
                                    every write needs human approval.
    Phase 2  assisted-coord     -- adds service-scheduling /
                                    confidentiality-concern flagging /
                                    supply-order-coordination writes,
                                    still approval.
    Phase 3  supervised auto    -- governor-clean, high-confidence
                                    `:log-service-record` and
                                    `:schedule-service-operation` (no
                                    document-release/data-privacy-
                                    finalization weight -- pure data
                                    logging and scheduling coordination)
                                    may auto-commit; `:coordinate-
                                    supply-order` also auto-commits
                                    once clean/confident PROVIDED the
                                    governor's own cost-threshold check
                                    does not mark it `high-stakes` (see
                                    `ofsup.governor/high-cost-supply-
                                    threshold-usd` -- the phase `:auto`
                                    set can only ALLOW auto-commit for
                                    an otherwise-clean proposal, it can
                                    never override a governor escalate/
                                    hold). `:flag-confidentiality-
                                    concern` NEVER auto-commits, at any
                                    phase.

  UNLIKE `cloud-itonami-isic-8020` (where only ONE op is ever
  auto-eligible, because every other write carries real dispatch/
  installation weight), this domain's risk profile is more
  differentiated -- see `ofsup.governor` ns docstring `SCOPE` /
  `UNLIKE`: scheduling a photocopying/document-preparation job and a
  routine, low-cost supply reorder do not carry the same real-world
  weight as a security-systems installation dispatch, so both are
  phase-3 auto-eligible when the governor is otherwise clean.
  `:flag-confidentiality-concern` is the sole permanent exception --
  deliberately ABSENT from every phase's `:auto` set, including phase
  3, because surfacing a confidentiality concern must ALWAYS reach a
  human; `ofsup.governor`'s own `high-stakes` gate enforces the same
  invariant independently (it is unconditionally a member of
  `high-stakes`). Two layers, not one, agree on this specific op.")

(def read-ops  #{})
(def write-ops #{:log-service-record :schedule-service-operation
                  :flag-confidentiality-concern :coordinate-supply-order})

;; NOTE the invariant: `:flag-confidentiality-concern` is a member of
;; `write-ops` (governor-gated like any write) but is NEVER a member of
;; any phase's `:auto` set below. Do not add it there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}."
  {0 {:label "read-only"             :writes #{}                                     :auto #{}}
   1 {:label "assisted-logging"      :writes #{:log-service-record}                  :auto #{}}
   2 {:label "assisted-coordination" :writes write-ops                               :auto #{}}
   3 {:label "supervised-auto"       :writes write-ops
      :auto #{:log-service-record :schedule-service-operation :coordinate-supply-order}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:flag-confidentiality-concern` is never auto-eligible at any
    phase, so it always escalates once the governor clears it (or
    holds if the governor doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map an Office Support Governor verdict to a base disposition before
  the phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
