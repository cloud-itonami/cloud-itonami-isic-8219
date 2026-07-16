(ns ofsup.ofsupllm
  "OfficeSupport-LLM client -- the *contained intelligence node* for the
  office-support-services (photocopying / document preparation /
  mailing) operations-coordination actor.

  It normalizes/registers a client's job (document-count/turnaround)
  record, drafts a service-scheduling coordination proposal, drafts a
  confidentiality-concern flag, and drafts a supply-order (paper/
  toner/equipment) coordination proposal. CRITICAL: it is a smart-but-
  untrusted advisor, and it is scoped to COORDINATION only -- it has
  NO authority to finalize a data-privacy-compliance decision over a
  client's documents and NO authority to release/disclose a client's
  documents (see `ofsup.governor` ns docstring `SCOPE`). It returns a
  *proposal* (`:effect` is ALWAYS the literal `:propose`), never a
  committed record and never a real document release or data-privacy-
  compliance determination. Every output is censored downstream by
  `ofsup.governor` before anything touches the SSoT, and `:flag-
  confidentiality-concern` proposals NEVER auto-commit at any phase --
  see README `Actuation`.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the spec-basis AND
                                 ; scope-exclusion gates
     :cites      [kw|str ..]    ; facts/sources the LLM used -- SCANNED too
     :effect     :propose       ; ALWAYS this literal value -- this actor
                                 ; never actuates (ofsup.governor's own
                                 ; `effect-not-propose-violations` hard-
                                 ; enforces this)
     :action     kw             ; the SSoT mutation this proposal, if
                                 ; approved, would apply -- one of the
                                 ; closed `ofsup.governor/allowed-
                                 ; actions`
     :value      map            ; payload for :action
     :stake      kw|nil         ; :office-support/flag-concern |
                                 ; :office-support/high-cost-supply |
                                 ; nil -- see `ofsup.governor/high-stakes`
     :confidence 0..1}

  IMPORTANT re: the fleet-wide self-tripping-bug class (see
  `ofsup.governor/scope-exclusion-actions` docstring): every disclaimer
  below DENIES having data-privacy-compliance-finalization/document-
  release authority using DIFFERENT wording than the full finalization-
  action phrases the governor scans for -- and
  `ofsup.governor-self-trip-test` proves this holds for every proposal
  this advisor's default `infer` can produce, rather than relying on
  wording care alone."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [ofsup.facts :as facts]
            [ofsup.governor :as governor]
            [ofsup.store :as store]
            [langchain.model :as model]))

(defn- propose-log-service-record
  "Draft the client-job record UPDATE + its jurisdictional registration
  citation. The LLM only normalizes/validates the patch and cites the
  jurisdiction's own official source; it does not invent the patch
  fields, the jurisdiction, or a spec-basis for a jurisdiction with
  none on file."
  [db {:keys [subject patch no-spec?]}]
  (let [existing (store/job db subject)
        base (merge existing patch)
        iso3 (if no-spec? "ATL" (:jurisdiction base))
        sb (facts/spec-basis iso3)]
    (if (nil? sb)
      {:summary    (str subject " の記録更新: " iso3 " の公式spec-basisが見つかりません")
       :rationale  "ofsup.facts に未登録の法域。要件を推測で作らない。この提案は記録の登録・検証を完了させない。"
       :cites      []
       :effect     :propose
       :action     :job/log
       :value      {:patch (assoc patch :jurisdiction iso3) :spec-basis nil :legal-basis nil}
       :stake      nil
       :confidence 0.9}
      {:summary    (str subject " のクライアント/ジョブ記録を更新し、" iso3
                        " (" (:owner-authority sb) ") 向けに登録")
       :rationale  (str "公式ソース: " (:provenance sb) " / 法的根拠: " (:legal-basis sb)
                        " -- 本提案は記録の登録のみで、データプライバシー適合性の確定や"
                        "文書の開示判断は行わない")
       :cites      [(:legal-basis sb) (:provenance sb)]
       :effect     :propose
       :action     :job/log
       :value      {:patch patch :spec-basis (:provenance sb) :legal-basis (:legal-basis sb)}
       :stake      nil
       :confidence 0.95})))

(defn- propose-schedule-service-operation
  "Draft a SERVICE-OPERATION scheduling coordination proposal -- a
  scheduling DRAFT, never a real job dispatch or document release.
  Never sets `:stake` -- unlike `:flag-confidentiality-concern`/high-
  cost `:coordinate-supply-order`, a photocopying/document-preparation
  job schedule carries no data-privacy-compliance-finalization or
  document-release weight of its own (see `ofsup.governor` ns
  docstring `UNLIKE`), so it is governed by the ordinary confidence-
  floor + phase `:auto`-membership gate only."
  [db {:keys [subject]}]
  (let [c (store/job db subject)
        registered? (and c (:registered? c))
        registration-ok? (and c (or (not (:requires-registration? c)) (:registration-confirmed? c)))
        concern-clear? (and c (or (not (:confidentiality-concern-raised? c)) (:confidentiality-concern-resolved? c)))]
    {:summary    (str subject " 向けサービススケジュール調整案"
                      (when c (str " (client=" (:client c) ")")))
     :rationale  (if c
                   (str "registered?=" registered? " registration-confirmed?=" registration-ok?
                        " confidentiality-concern-clear?=" concern-clear?
                        " -- this is a scheduling coordination draft only;"
                        " it does not decide any data-privacy-compliance"
                        " determination and does not release any client"
                        " documents -- those remain a certified office-"
                        " support operator's own act.")
                   "jobが見つかりません")
     :cites      (if c [subject] [])
     :effect     :propose
     :action     :job/mark-scheduled
     :value      {:job-id subject}
     :stake      nil
     :confidence (if (and registered? registration-ok? concern-clear?) 0.9 0.3)}))

(defn- propose-flag-confidentiality-concern
  "Draft a CONFIDENTIALITY-CONCERN flag -- surfaces a document-
  confidentiality/data-breach concern for human review. ALWAYS `:stake
  :office-support/flag-concern` -- flagging a concern must ALWAYS
  reach a human sign-off; this actor never resolves or dismisses a
  concern itself, and never decides a data-privacy-compliance
  determination or a client-document release. See README `Actuation`:
  no phase ever adds this op to a phase's `:auto` set (`ofsup.phase`);
  the governor also always escalates on `:office-support/flag-
  concern`. Two independent layers agree, deliberately."
  [db {:keys [subject note]}]
  (let [c (store/job db subject)]
    {:summary    (str subject " について秘密保持/データ侵害の懸念事項を報告"
                      (when note (str ": " note)))
     :rationale  (str "this proposal only SURFACES a concern for human"
                      " review; it does not resolve or dismiss the"
                      " concern, and it does not decide any data-"
                      " privacy-compliance determination or any client-"
                      " document release -- a certified office-support"
                      " operator always makes that call.")
     :cites      (if c [subject] [])
     :effect     :propose
     :action     :job/flag-confidentiality-concern
     :value      {:job-id subject :note (or note "unspecified document-confidentiality/data-breach concern")}
     :stake      :office-support/flag-concern
     :confidence 0.95}))

(defn- propose-coordinate-supply-order
  "Draft a SUPPLY-ORDER coordination proposal -- paper/toner/equipment
  procurement coordination, never a real purchase commitment or
  shipment release. `:stake` is `:office-support/high-cost-supply`
  ONLY when `estimated-cost-usd` exceeds `ofsup.governor/high-cost-
  supply-threshold-usd` (a routine consumables reorder is low-stakes;
  a large equipment/procurement commitment always needs a human) --
  see `ofsup.governor` ns docstring `UNLIKE`. A missing/non-numeric
  estimate is treated conservatively as high-cost by
  `ofsup.governor/high-cost-supply-order?` -- never silently
  auto-eligible on an absent estimate."
  [db {:keys [subject estimated-cost-usd]}]
  (let [c (store/job db subject)
        registered? (and c (:registered? c))
        concern-clear? (and c (or (not (:confidentiality-concern-raised? c)) (:confidentiality-concern-resolved? c)))
        already-open? (and c (:supply-coordination-open? c))
        high-cost? (governor/high-cost-supply-order? estimated-cost-usd)]
    {:summary    (str subject " 向け資材調達調整案 (見積: " estimated-cost-usd " USD)")
     :rationale  (if c
                   (str "registered?=" registered? " confidentiality-concern-clear?=" concern-clear?
                        " supply-coordination-open?=" already-open?
                        " estimated-cost-usd=" estimated-cost-usd
                        " -- this is a supply-procurement coordination"
                        " draft only; it does not commit any purchase and"
                        " does not release any inventory -- that remains"
                        " a certified office-support operator's own act.")
                   "jobが見つかりません")
     :cites      (if c [subject] [])
     :effect     :propose
     :action     :job/mark-supply-coordinated
     :value      {:job-id subject :estimated-cost-usd estimated-cost-usd}
     :stake      (when high-cost? :office-support/high-cost-supply)
     :confidence (if (and registered? concern-clear? (not already-open?)) 0.9 0.3)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :log-service-record          (propose-log-service-record db request)
    :schedule-service-operation  (propose-schedule-service-operation db request)
    :flag-confidentiality-concern (propose-flag-confidentiality-concern db request)
    :coordinate-supply-order     (propose-coordinate-supply-order db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :propose :action :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは地域のオフィスサポート事業者(コピー/document preparation/郵送)の"
       "運行コーディネーションエージェントの助言者です。あなたにはデータプライ"
       "バシー適合性の確定権限も、顧客文書の開示・提供権限もありません。与えら"
       "れた事実のみに基づき、提案を1つだけEDNマップで返します。説明や前置きは"
       "一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) :effect(常に:propose) "
       ":action(:job/log|:job/mark-scheduled|:job/flag-confidentiality-concern|"
       ":job/mark-supply-coordinated) "
       ":stake(:office-support/flag-concern か "
       ":office-support/high-cost-supply か nil) :confidence(0..1)。\n"
       "重要: 登録されていない法域の要件を絶対に創作してはいけません。"
       "文書作成者登録の状況や秘密保持懸念の解消状況を偽って報告しては"
       "いけません。データプライバシー適合性の確定判断や顧客文書の開示・提供を"
       "絶対に提案してはいけません -- あなたの役割は調整案の提示のみです。"))

(defn- facts-for [st {:keys [subject]}]
  {:job (store/job st subject)})

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the Office Support Governor
  escalates/holds -- an LLM hiccup can never auto-schedule a service
  operation or auto-coordinate a supply order."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :propose))
          (update :action #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :propose :action :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :ofsupllm-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
