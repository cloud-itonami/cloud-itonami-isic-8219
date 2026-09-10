(ns ofsup.facts
  "Per-jurisdiction data-privacy/document-handling registration catalog --
  the G2-style spec-basis table the Office Support Governor checks every
  `:log-service-record` proposal against ('did the advisor cite an
  OFFICIAL public source for this jurisdiction's document-handling/
  data-privacy registration requirements, or did it invent one?').

  This repo's own README `Scope note` already identifies the exact
  regulatory concerns this catalog encodes: unauthorized-practice-of-law
  statutes restricting document preparers from giving legal advice
  (California requires registration as a Legal Document Assistant for
  fee-based legal-document preparation, Bus. & Prof. Code SS6400 et
  seq.), and data-privacy obligations that apply to the personal and
  financial documents customers bring in to be copied or prepared (UK
  GDPR / Data Protection Act 2018 notification with the Information
  Commissioner's Office; Japan's APPI (個人情報の保護に関する法律) obligations
  administered by the Personal Information Protection Commission). This
  namespace does not extend that set with anything not already asserted
  in the README -- adding coverage means adding a real, citable catalog
  entry, never fabricating one.

  Like every sibling actor's `facts` namespace, coverage is reported
  HONESTLY (see `coverage`): a jurisdiction not in this table has NO
  spec-basis, full stop -- the advisor must not fabricate one, and the
  governor holds if it tries.")

(def catalog
  "iso3 -> requirement map. `:required-evidence` mirrors the generic
  client-job registration evidence set (PLUS a document-preparer-
  registration reference for fee-based legal-document-preparation
  jobs); `:legal-basis` / `:owner-authority` / `:provenance` are the G2
  citation the governor requires before any `:log-service-record`
  proposal can commit."
  {"USA" {:name "United States (California)"
          :owner-authority "California county clerk registration under the Legal Document Assistants Act"
          :legal-basis "California Business and Professions Code SS6400 et seq. (Legal Document Assistants Act) -- fee-based legal-document preparation requires county registration"
          :national-spec "California Legal Document Assistant registration and bonding requirements for fee-based legal-document preparation, distinct from ordinary photocopying/typing services"
          :provenance "https://leginfo.legislature.ca.gov/faces/codes_displaySection.xhtml?sectionNum=6400.&lawCode=BPC"
          :required-evidence ["client job registration record"
                               "document-count/turnaround log entry"
                               "Legal Document Assistant registration reference (fee-based legal-document preparation jobs only)"
                               "customer data-handling/confidentiality acknowledgment record"]}
   "GBR" {:name "United Kingdom"
          :owner-authority "Information Commissioner's Office (ICO)"
          :legal-basis "UK GDPR / Data Protection Act 2018 -- data controller registration (notification) requirement"
          :national-spec "ICO data-protection-fee notification for organisations processing customers' personal data, applicable to document-preparation/photocopying services handling client personal/financial documents"
          :provenance "https://ico.org.uk/for-organisations/data-protection-fee/"
          :required-evidence ["client job registration record"
                               "document-count/turnaround log entry"
                               "ICO data-protection-fee registration reference"
                               "customer data-handling/confidentiality acknowledgment record"]}
   "JPN" {:name "Japan"
          :owner-authority "個人情報保護委員会 (Personal Information Protection Commission, PPC)"
          :legal-basis "個人情報の保護に関する法律 (APPI) 第２２条 -- 個人情報取扱事業者の安全管理措置義務"
          :national-spec "個人情報取扱事業者の安全管理措置ガイドライン (personal-information-handling-business-operator safety-management-measures guideline), applicable to document-preparation/photocopying services handling client personal documents"
          :provenance "https://www.ppc.go.jp/"
          :required-evidence ["顧客ジョブ登録記録 (client job registration record)"
                               "文書件数・ターンアラウンド記録 (document-count/turnaround log entry)"
                               "個人情報取扱事業者安全管理措置 reference"
                               "顧客データ取扱・秘密保持確認記録 (customer data-handling/confidentiality acknowledgment record)"]}})

(defn spec-basis
  "The jurisdiction's requirement map, or nil -- nil means NO spec-basis,
  and the governor must hold any proposal that tries to register a
  client-job record on it."
  [iso3]
  (get catalog iso3))

(defn coverage
  "Honest coverage report: how many of the requested jurisdictions actually
  have a spec-basis entry. Never report a missing jurisdiction as covered."
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-isic-8219 R0: " (count catalog)
                 " jurisdictions seeded with an official spec-basis. "
                 "This is a starting catalog, not a survey of all ~194 "
                 "jurisdictions -- extend `ofsup.facts/catalog`, "
                 "never fabricate a jurisdiction's document-handling/"
                 "data-privacy registration requirements.")})))

(defn required-evidence-satisfied?
  "Does `submitted` (a set/coll of evidence keywords or strings) satisfy
  every evidence item listed for `iso3`? Missing spec-basis -> never
  satisfied."
  [iso3 submitted]
  (when-let [{:keys [required-evidence]} (spec-basis iso3)]
    (let [need (count required-evidence)
          have (count (filter (set submitted) required-evidence))]
      (= need have))))

(defn evidence-checklist [iso3]
  (:required-evidence (spec-basis iso3) []))
