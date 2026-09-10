(ns ofsup.registry-test
  (:require [clojure.test :refer [deftest is]]
            [ofsup.registry :as r]))

;; ----------------------------- register-service-schedule -----------------------------

(deftest schedule-is-a-draft-not-a-real-dispatch
  (let [result (r/register-service-schedule "job-1" "USA" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest schedule-assigns-schedule-number
  (let [result (r/register-service-schedule "job-1" "USA" 7)]
    (is (= (get result "schedule_number") "USA-SVC-000007"))
    (is (= (get-in result ["record" "job_id"]) "job-1"))
    (is (= (get-in result ["record" "kind"]) "service-schedule-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest schedule-validation-rules
  (is (thrown? Exception (r/register-service-schedule "" "USA" 0)))
  (is (thrown? Exception (r/register-service-schedule "job-1" "" 0)))
  (is (thrown? Exception (r/register-service-schedule "job-1" "USA" -1))))

;; ----------------------------- register-supply-order -----------------------------

(deftest supply-order-is-a-draft-not-a-real-purchase
  (let [result (r/register-supply-order "job-1" "USA" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest supply-order-assigns-supply-number
  (let [result (r/register-supply-order "job-1" "USA" 7)]
    (is (= (get result "supply_order_number") "USA-SUP-000007"))
    (is (= (get-in result ["record" "job_id"]) "job-1"))
    (is (= (get-in result ["record" "kind"]) "supply-order-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest supply-order-validation-rules
  (is (thrown? Exception (r/register-supply-order "" "USA" 0)))
  (is (thrown? Exception (r/register-supply-order "job-1" "" 0)))
  (is (thrown? Exception (r/register-supply-order "job-1" "USA" -1))))

(deftest history-is-append-only
  (let [c1 (r/register-service-schedule "job-1" "USA" 0)
        hist (r/append [] c1)
        c2 (r/register-service-schedule "job-2" "USA" 1)
        hist2 (r/append hist c2)]
    (is (= 2 (count hist2)))
    (is (= "USA-SVC-000000" (get-in hist2 [0 "record_id"])))
    (is (= "USA-SVC-000001" (get-in hist2 [1 "record_id"])))))
