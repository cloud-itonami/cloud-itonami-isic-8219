(ns ofsup.governor
  "Office Support Governor -- the independent compliance layer that
  earns the OfficeSupport-LLM the right to commit. The LLM has no
  notion of jurisdictional document-handling/data-privacy law, whether
  a job's own client record has actually been independently verified/
  registered, whether a fee-based legal-document-preparation job's
  document-preparer registration has actually been confirmed, whether
  an open confidentiality concern has actually been resolved, or when
  an act stops being a draft coordination note and becomes something
  this actor must NEVER be allowed to represent (a real data-privacy-
  compliance determination or a real release/disclosure of a client's
  documents), so this MUST be a separate system able to *reject* a
  proposal and fall back to HOLD.

  ================================================================
  SCOPE, stated as a structural invariant, not a policy preference
  ================================================================
  This actor is an office-support-services (photocopying / document
  preparation / mailing) OPERATIONS COORDINATION actor. It is NOT the
  authority that finalizes a data-privacy-compliance decision over a
  client's documents, and NOT the authority that releases or discloses
  a client's documents -- both remain a certified office-support
  operator's own act, never this actor's. Every proposal it can ever
  produce has a literal `:effect :propose` (never an actuation) and an
  `:action` drawn from a FOUR-MEMBER closed allowlist
  (`ofsup.governor/allowed-actions`) that maps 1:1 to the FOUR ops in
  `ofsup.governor/allowed-ops` (`:log-service-record`/`:schedule-
  service-operation`/`:flag-confidentiality-concern`/`:coordinate-
  supply-order`). A proposal to directly finalize a data-privacy-
  compliance decision over client documents is not merely disallowed
  by policy -- it CANNOT be represented in this closed allowlist at
  all, so `action-allowlist-violations` hard-blocks it structurally
  even if an advisor somehow proposed one. `scope-exclusion-violations`
  is a SECOND, independent layer: it text-scans the proposal's own
  rationale/summary for a small set of finalization/execution ACTION
  phrases (never a bare noun -- see that check's own docstring for
  why) so a proposal that merely NAMES a forbidden finalization act in
  its prose (without setting a matching `:action`) is caught too. Two
  independent layers, matching the two-layer discipline every prior
  sibling actor's own real-actuation gate uses (see `high-stakes`
  below and `ofsup.phase`).

  `:flag-confidentiality-concern` in particular must ALWAYS escalate
  to human sign-off -- it is never a member of any phase's `:auto`
  set, at any phase, and it is unconditionally a member of
  `high-stakes` -- this actor never resolves or dismisses a
  confidentiality concern itself.

  UNLIKE several sibling actors where every non-logging write op is
  unconditionally high-stakes, this domain's risk profile is more
  differentiated (see README `Scope note`): scheduling a photocopying/
  document-preparation job carries far less real-world weight than,
  say, scheduling a security-systems installation, so `:schedule-
  service-operation` is governed by the ordinary confidence-floor gate
  and phase `:auto` eligibility like `:log-service-record`, NOT by an
  unconditional `high-stakes` stake. `:coordinate-supply-order` sits in
  between: a routine paper/toner reorder is low-stakes and phase-3
  auto-eligible when clean and confident, but the advisor sets a
  `high-stakes` stake once the proposal's own estimated cost exceeds
  `high-cost-supply-threshold-usd` -- a large equipment/procurement
  commitment always needs a human, a routine consumables reorder does
  not.

  Ten checks, in priority order, ALL HARD violations: a human approver
  CANNOT override them. The confidence/high-cost gate is SOFT: it asks
  a human to look (low confidence / high-stakes), and the human may
  approve -- but see `ofsup.phase`: `:flag-confidentiality-concern` is
  NEVER in any phase's `:auto` set, at any phase, independently of the
  high-stakes stake check. Two independent layers agree on that one op.

    1.  Effect-not-:propose      -- structural: every proposal this
                                     actor emits must carry the literal
                                     `:effect :propose` -- it never
                                     actuates.
    2.  Op not allowlisted       -- the request's `:op` must be one of
                                     the four closed-allowlist ops.
    3.  Action not allowlisted   -- the proposal's `:action` must be
                                     one of the four closed-allowlist
                                     actions -- structurally excludes
                                     any direct data-privacy-compliance-
                                     finalization or document-release
                                     action (see SCOPE above).
    4.  Scope-exclusion          -- the proposal's own rationale/
                                     summary text must not name a
                                     finalization/execution ACTION this
                                     actor must never perform (SECOND,
                                     independent layer to #3).
    5.  Spec-basis               -- for `:log-service-record`, did the
                                     advisor cite an OFFICIAL source
                                     (`ofsup.facts`), or invent one?
    6.  Record not verified      -- for `:schedule-service-operation`/
                                     `:flag-confidentiality-concern`/
                                     `:coordinate-supply-order`, has the
                                     subject job's own client record
                                     actually been independently
                                     verified/registered (via a
                                     committed `:log-service-record`)?
                                     The HARD invariant this vertical's
                                     own README states: 'a client/job
                                     record must be independently
                                     verified/registered before any
                                     action' -- applied to ALL THREE
                                     non-registration ops, not only the
                                     highest-stakes one.
    7.  Document-preparer
        registration unconfirmed  -- for `:schedule-service-operation`,
                                     INDEPENDENTLY verify that if the
                                     job requires fee-based legal-
                                     document-preparation registration,
                                     its own `:registration-confirmed?`
                                     fact is true. Never trust the
                                     advisor's self-reported confidence
                                     alone.
    8.  Open confidentiality
        concern                   -- for `:schedule-service-operation`/
                                     `:coordinate-supply-order`, an
                                     unresolved confidentiality concern
                                     on file for the subject job
                                     (`:confidentiality-concern-raised?
                                     true` AND `:confidentiality-
                                     concern-resolved? false`) is a
                                     HARD, un-overridable hold.
    9.  Already scheduled        -- for `:schedule-service-operation`,
                                     refuses to double-schedule the SAME
                                     job, off a dedicated `:scheduled?`
                                     fact (never a `:status` value).
    10. Already coordinating     -- for `:coordinate-supply-order`,
                                     refuses to open a SECOND supply-
                                     order request while one is already
                                     open, off a dedicated `:supply-
                                     coordination-open?` fact (never a
                                     `:status` value)."
  (:require [clojure.string :as str]
            [ofsup.store :as store]))

(def confidence-floor 0.6)

(def high-cost-supply-threshold-usd
  "Above this estimated cost, a supply-order proposal is treated as
  `high-stakes` (unconditional escalate) regardless of confidence --
  see ns docstring. A routine paper/toner reorder sits well under
  this; a large equipment/procurement commitment does not."
  500)

(defn high-cost-supply-order?
  "Is `estimated-cost-usd` above the governor's own cost threshold?
  Non-numeric/nil is treated conservatively as high-cost (never
  silently auto-eligible on a missing estimate)."
  [estimated-cost-usd]
  (or (not (number? estimated-cost-usd))
      (> estimated-cost-usd high-cost-supply-threshold-usd)))

(def allowed-ops
  "The closed op allowlist -- see this ns docstring `SCOPE`. Nothing
  outside this four-member set is a valid `:op`, structurally."
  #{:log-service-record :schedule-service-operation
    :flag-confidentiality-concern :coordinate-supply-order})

(def allowed-actions
  "The closed `:action` allowlist -- 1:1 with `allowed-ops`. A direct
  data-privacy-compliance-finalization action or a document-release/
  disclosure action is not a member of this set and can therefore
  never be represented as a proposal `:action` this governor would let
  through."
  #{:job/log :job/mark-scheduled
    :job/flag-confidentiality-concern :job/mark-supply-coordinated})

(def high-stakes
  "Stakes grave enough to always require a human, even when the
  governor is otherwise clean. `:office-support/flag-concern` is set
  unconditionally by the advisor for every `:flag-confidentiality-
  concern` proposal (see ns docstring UNLIKE note); `:office-support/
  high-cost-supply` is set by the advisor only when a `:coordinate-
  supply-order` proposal's own estimated cost exceeds
  `high-cost-supply-threshold-usd`. `:log-service-record` and
  `:schedule-service-operation` never set a `:stake` -- they are
  governed by the ordinary confidence-floor + phase `:auto`-membership
  gate only."
  #{:office-support/flag-concern :office-support/high-cost-supply})

;; ------------------------- scope-exclusion terms -------------------------

(def scope-exclusion-actions
  "Finalization/execution ACTION phrases (never a bare noun) naming a
  direct data-privacy-compliance-finalization decision or a document-
  release/disclosure decision this actor must NEVER finalize. This
  fleet has independently rediscovered, in multiple sibling repos, the
  SAME bug class: a scope-exclusion term list phrased as a bare noun
  (e.g. \"release\", \"disclosure\", \"compliant\") accidentally
  matches inside this actor's OWN default mock-advisor's disclaimer
  text for a legitimate, allowed proposal (every disclaimer in
  `ofsup.ofsupllm` says things like 'this proposal does not decide any
  data-privacy-compliance determination' -- a bare noun like
  \"compliance\" or \"determination\" would match that sentence and
  self-block the happy path). Phrasing each term as the FULL
  finalization-action phrase avoids this: a disclaimer that merely
  DENIES having the authority never contains the literal action phrase
  itself as a contiguous substring. `ofsup.governor-self-trip-test`
  exercises every default proposal this actor's advisor can produce
  and asserts NONE of them trip this check -- that test, not careful
  wording alone, is the real guarantee."
  ["finalize the data-privacy-compliance determination for these documents"
   "certify these documents as data-privacy compliant"
   "approve release of these client documents without a confidentiality review"
   "authorize disclosure of these client documents to a third party"
   "release these client documents without client authorization"
   "determine this data breach does not require notification"
   "close this confidentiality concern without a review"
   "waive the confidentiality review for this job"
   "override the confidentiality hold on this job"
   "clear this job as privacy-compliant without verification"])

;; ----------------------------- checks -----------------------------

(defn- effect-not-propose-violations
  "Every proposal this actor emits must carry the literal `:effect
  :propose` -- it never actuates. Evaluated UNCONDITIONALLY, on every
  op."
  [_request proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str "提案の:effectが:proposeではありません(" (:effect proposal) ")")}]))

(defn- op-allowlist-violations
  "The request's `:op` must be one of the four closed-allowlist ops.
  Evaluated UNCONDITIONALLY."
  [{:keys [op]} _proposal]
  (when-not (contains? allowed-ops op)
    [{:rule :op-not-allowlisted
      :detail (str op " は許可された操作(op)一覧に含まれません")}]))

(defn- action-allowlist-violations
  "The proposal's `:action` must be one of the four closed-allowlist
  actions -- structurally excludes any data-privacy-compliance-
  finalization or document-release/disclosure action. Evaluated
  UNCONDITIONALLY."
  [_request proposal]
  (when-not (contains? allowed-actions (:action proposal))
    [{:rule :action-not-allowlisted
      :detail (str (:action proposal) " は許可されたaction一覧に含まれません -- データプライバシー適合性の確定/顧客文書の開示は決して許可されない")}]))

(defn- scope-exclusion-violations
  "The proposal's own rationale/summary text must not name a
  finalization/execution ACTION this actor must never perform -- see
  `scope-exclusion-actions` docstring for why these are phrased as
  full action phrases, never bare nouns. Evaluated UNCONDITIONALLY."
  [_request proposal]
  (let [text (str/lower-case (str (:summary proposal) " " (:rationale proposal)))]
    (when (some #(str/includes? text (str/lower-case %)) scope-exclusion-actions)
      [{:rule :scope-exclusion-violation
        :detail "提案文言がデータプライバシー適合性の確定/顧客文書の開示・提供の確定行為に該当する表現を含みます -- 恒久的にブロック"}])))

(defn- spec-basis-violations
  "A `:log-service-record` proposal with no spec-basis citation is a
  HARD violation -- never invent a jurisdiction's document-handling/
  data-privacy registration requirements."
  [{:keys [op]} proposal]
  (when (= op :log-service-record)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式spec-basisの引用が無い提案は法域要件として扱えない"}]))))

(defn- record-not-verified-violations
  "For `:schedule-service-operation`/`:flag-confidentiality-concern`/
  `:coordinate-supply-order`, the subject job's own client record must
  have ALREADY been independently verified/registered (a committed
  `:log-service-record`) -- the HARD invariant this vertical's own
  README states applies before ANY of these three ops, not only the
  highest-stakes one."
  [{:keys [op subject]} st]
  (when (contains? #{:schedule-service-operation :flag-confidentiality-concern :coordinate-supply-order} op)
    (let [c (store/job st subject)]
      (when-not (true? (:registered? c))
        [{:rule :record-not-verified
          :detail (str subject " のクライアント/ジョブ記録が未登録・未検証の状態での提案")}]))))

(defn- registration-unconfirmed-violations
  "For `:schedule-service-operation`, INDEPENDENTLY verify that if the
  job requires fee-based legal-document-preparation registration, its
  own `:registration-confirmed?` fact is true. Evaluated
  UNCONDITIONALLY (every schedule proposal for a registration-
  requiring job needs a confirmed registration)."
  [{:keys [op subject]} st]
  (when (= op :schedule-service-operation)
    (let [c (store/job st subject)]
      (when (and (:requires-registration? c) (not (true? (:registration-confirmed? c))))
        [{:rule :registration-unconfirmed
          :detail (str subject " は文書作成者登録(Legal Document Assistant等)が必要だが、確認が未完了 -- サービススケジュール提案は進められない")}]))))

(defn- open-confidentiality-concern-violations
  "An unresolved confidentiality concern -- already on file for the
  subject job (`:confidentiality-concern-raised? true` AND
  `:confidentiality-concern-resolved? false`) -- is a HARD,
  un-overridable hold. Evaluated UNCONDITIONALLY across `:schedule-
  service-operation` and `:coordinate-supply-order`."
  [{:keys [op subject]} st]
  (when (contains? #{:schedule-service-operation :coordinate-supply-order} op)
    (let [c (store/job st subject)]
      (when (and (true? (:confidentiality-concern-raised? c)) (not (true? (:confidentiality-concern-resolved? c))))
        [{:rule :open-confidentiality-concern
          :detail (str subject " は未解決の秘密保持懸念がある -- サービススケジュール/資材調達調整提案は進められない")}]))))

(defn- already-scheduled-violations
  "For `:schedule-service-operation`, refuses to double-schedule the
  SAME job, off a dedicated `:scheduled?` fact (never a `:status`
  value)."
  [{:keys [op subject]} st]
  (when (= op :schedule-service-operation)
    (when (store/job-already-scheduled? st subject)
      [{:rule :already-scheduled
        :detail (str subject " は既にサービススケジュールが調整済み")}])))

(defn- already-coordinating-violations
  "For `:coordinate-supply-order`, refuses to open a SECOND supply-
  order request while one is already open, off a dedicated `:supply-
  coordination-open?` fact (never a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :coordinate-supply-order)
    (when (store/job-supply-already-open? st subject)
      [{:rule :already-coordinating
        :detail (str subject " は既に資材調達調整が進行中")}])))

(defn check
  "Censors an OfficeSupport-LLM proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (effect-not-propose-violations request proposal)
                           (op-allowlist-violations request proposal)
                           (action-allowlist-violations request proposal)
                           (scope-exclusion-violations request proposal)
                           (spec-basis-violations request proposal)
                           (record-not-verified-violations request st)
                           (registration-unconfirmed-violations request st)
                           (open-confidentiality-concern-violations request st)
                           (already-scheduled-violations request st)
                           (already-coordinating-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
