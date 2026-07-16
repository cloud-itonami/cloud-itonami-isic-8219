(ns ofsup.governor-self-trip-test
  "Dedicated regression test for a bug class this exact fleet has
  independently rediscovered and fixed in multiple sibling repos: a
  governor's own scope-exclusion term list phrased as a bare noun
  (e.g. \"release\", \"disclosure\", \"compliant\") accidentally matches
  inside the mock advisor's OWN default rationale/disclaimer text for
  a legitimate, allowed proposal -- causing the actor to self-block on
  its own happy path.

  `ofsup.governor/scope-exclusion-actions` is deliberately phrased as
  full finalization/execution ACTION phrases rather than bare nouns
  (see that var's own docstring for the reasoning). This test does not
  merely trust that phrasing choice by inspection -- it runs the
  DEFAULT mock advisor's `infer` for every op in the closed allowlist,
  across every demo job (so every distinct rationale branch this
  advisor can produce is exercised, including the registration/
  concern/already-open/no-spec-basis branches), and asserts NONE of
  the resulting proposals trip `scope-exclusion-violations` -- the
  actual guarantee, not wording care alone."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ofsup.governor :as governor]
            [ofsup.ofsupllm :as llm]
            [ofsup.store :as store]))

(defn- scope-exclusion-rule-fired? [request proposal st]
  (let [verdict (governor/check request {:actor-id "op-1"} proposal st)]
    (boolean (some #{:scope-exclusion-violation} (mapv :rule (:violations verdict))))))

(deftest default-advisor-never-self-trips-the-scope-exclusion-check
  (let [db (store/seed-db)
        advisor (llm/mock-advisor)
        subjects (mapv :id (store/all-jobs db))
        requests (into
                  ;; every op, against every seeded job (including a
                  ;; couple of estimated-cost-usd values for
                  ;; coordinate-supply-order, so both the high-cost and
                  ;; low-cost rationale branches are exercised)
                  (concat
                   (for [op [:log-service-record :schedule-service-operation
                             :flag-confidentiality-concern]
                         subject subjects]
                     {:op op :subject subject})
                   (for [subject subjects
                         cost [120 900 nil]]
                     {:op :coordinate-supply-order :subject subject :estimated-cost-usd cost}))
                  ;; plus the dedicated no-spec-basis branch of :log-service-record
                  [{:op :log-service-record :subject "job-1" :no-spec? true}])]
    (doseq [{:keys [op subject] :as request} requests]
      (testing (str op " / " subject " / " (:estimated-cost-usd request))
        (let [proposal (llm/-advise advisor db request)]
          (is (not (scope-exclusion-rule-fired? request proposal db))
              (str "default advisor's own proposal self-tripped scope-exclusion: "
                   (pr-str proposal))))))))

(deftest default-advisor-proposals-never-mention-a-forbidden-finalization-phrase-literally
  (testing "belt-and-suspenders: directly assert none of governor's own exclusion phrases appear verbatim in any default proposal's rationale/summary"
    (let [db (store/seed-db)
          advisor (llm/mock-advisor)
          subjects (mapv :id (store/all-jobs db))]
      (doseq [op [:log-service-record :schedule-service-operation
                  :flag-confidentiality-concern :coordinate-supply-order]
              subject subjects]
        (let [proposal (llm/-advise advisor db {:op op :subject subject :estimated-cost-usd 250})
              text (str/lower-case (str (:summary proposal) " " (:rationale proposal)))]
          (doseq [phrase governor/scope-exclusion-actions]
            (is (not (str/includes? text (str/lower-case phrase)))
                (str op "/" subject " rationale unexpectedly contains exclusion phrase " (pr-str phrase)))))))))
