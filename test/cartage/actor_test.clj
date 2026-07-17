(ns cartage.actor-test
  (:require [clojure.test :refer [deftest is testing]]
            [cartage.actor :as actor]
            [cartage.advisor :as advisor]
            [cartage.governor :as governor]
            [cartage.store :as store]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-driver! st {:driver-id "D-1" :name "Driver Kobo"
                                :depot-id "depot-9" :verified? true})
    (store/register-depot! st {:depot-id "depot-9"
                               :max-maintenance-cost 800 :verified? true})
    st))

;; --- happy paths ------------------------------------------------------

(deftest commits-a-well-formed-trip-log-entry
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:op :log-trip-record :stake :low :driver-id "D-1" :depot-id "depot-9"
                  :trip-id "T-1" :fare-amount 12.5 :animal-condition-checkin :serviceable
                  :timestamp "2026-07-14T10:00:00Z"}
        result (actor/run-request! graph request {} "thread-1")]
    (is (= :done (:status result)))
    (is (some? (get-in result [:state :record])))
    (is (= 1 (count (store/records-of st "depot-9"))))))

(deftest commits-a-dispatch-operation-scheduling
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:op :schedule-dispatch-operation :stake :low :driver-id "D-1" :depot-id "depot-9"
                  :route-id "R-12" :proposed-time "2026-07-20T09:00:00Z" :vehicle-id "cart-4"}
        result (actor/run-request! graph request {} "thread-2")]
    (is (= :done (:status result)))
    (is (= 1 (count (store/records-of st "depot-9"))))))

(deftest commits-an-at-or-below-threshold-maintenance-order
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:op :coordinate-maintenance-order :stake :low :driver-id "D-1" :depot-id "depot-9"
                  :item "harness replacement" :cost 40 :vendor "CartageSupplyCo"}
        result (actor/run-request! graph request {} "thread-3")]
    (is (= :done (:status result)))
    (is (some? (get-in result [:state :record])))))

;; --- hard blocks --------------------------------------------------------

(deftest holds-an-unverified-driver-proposal
  (let [st (fresh-store)]
    (store/register-driver! st {:driver-id "D-2" :name "Unverified"
                                :depot-id "depot-9" :verified? false})
    (let [graph (actor/build-graph {:store st})
          request {:op :log-trip-record :stake :low :driver-id "D-2" :depot-id "depot-9"
                    :trip-id "T-1" :fare-amount 12.5 :animal-condition-checkin :serviceable
                    :timestamp "2026-07-14T10:00:00Z"}
          result (actor/run-request! graph request {} "thread-4")]
      (is (= :hold (:disposition (:state result))))
      (is (empty? (store/records-of st "depot-9"))))))

(deftest holds-an-unverified-depot-proposal
  (let [st (fresh-store)]
    (store/register-depot! st {:depot-id "depot-2" :max-maintenance-cost 800 :verified? false})
    (let [graph (actor/build-graph {:store st})
          request {:op :log-trip-record :stake :low :driver-id "D-1" :depot-id "depot-2"
                    :trip-id "T-1" :fare-amount 12.5 :animal-condition-checkin :serviceable
                    :timestamp "2026-07-14T10:00:00Z"}
          result (actor/run-request! graph request {} "thread-5")]
      (is (= :hold (:disposition (:state result))))
      (is (empty? (store/records-of st "depot-2"))))))

(deftest holds-a-depot-mismatch-proposal
  (let [st (fresh-store)]
    (store/register-depot! st {:depot-id "depot-3" :max-maintenance-cost 800 :verified? true})
    (let [graph (actor/build-graph {:store st})
          request {:op :log-trip-record :stake :low :driver-id "D-1" :depot-id "depot-3"
                    :trip-id "T-1" :fare-amount 12.5 :animal-condition-checkin :serviceable
                    :timestamp "2026-07-14T10:00:00Z"}
          result (actor/run-request! graph request {} "thread-6")]
      (is (= :hold (:disposition (:state result))))
      (is (empty? (store/records-of st "depot-3"))))))

(deftest holds-a-route-decision-attempt
  (testing "log-trip-record can never carry a route decision, even via a custom advisor"
    (let [st (fresh-store)
          rogue (reify advisor/Advisor
                  (-advise [_ _store _request]
                    {:op :log-trip-record :effect :propose :driver-id "D-1" :depot-id "depot-9"
                     :route-decision "take route R-9" :stake :low :confidence 0.9
                     :rationale "documented log-trip-record for depot depot-9"}))
          graph (actor/build-graph {:store st :advisor rogue})
          result (actor/run-request! graph {:op :log-trip-record} {} "thread-7")]
      (is (= :hold (:disposition (:state result))))
      (is (empty? (store/records-of st "depot-9"))))))

(deftest holds-a-dispatch-schedule-override-attempt
  (testing "schedule-dispatch-operation can never carry a live traffic-navigation override, even via a custom advisor"
    (let [st (fresh-store)
          rogue (reify advisor/Advisor
                  (-advise [_ _store _request]
                    {:op :schedule-dispatch-operation :effect :propose :driver-id "D-1" :depot-id "depot-9"
                     :route-navigation-directive "turn left at Main St" :stake :low :confidence 0.9
                     :rationale "documented schedule-dispatch-operation for depot depot-9"}))
          graph (actor/build-graph {:store st :advisor rogue})
          result (actor/run-request! graph {:op :schedule-dispatch-operation} {} "thread-8")]
      (is (= :hold (:disposition (:state result))))
      (is (empty? (store/records-of st "depot-9"))))))

(deftest holds-every-scope-excluded-op-attempt-even-via-a-rogue-advisor
  (testing "no path through this actor can finalize a route/traffic-navigation decision, decide an animal-welfare/
            treatment disposition, or override a driver's on-road/animal-handling safety judgment -- proven by
            forcing a rogue advisor to propose each named op"
    (doseq [op governor/scope-excluded-ops]
      (let [st (fresh-store)
            rogue (reify advisor/Advisor
                    (-advise [_ _store _request]
                      {:op op :effect :propose :driver-id "D-1" :depot-id "depot-9"
                       :stake :low :confidence 0.99
                       :rationale (str "documented " (name op) " for depot depot-9")}))
            graph (actor/build-graph {:store st :advisor rogue})
            result (actor/run-request! graph {:op op} {} (str "thread-scope-" (name op)))]
        (is (= :hold (:disposition (:state result))) (str "op " op " was not held"))
        (is (empty? (store/records-of st "depot-9")) (str "op " op " committed a record"))))))

(deftest holds-a-scope-excluded-rationale-attempt-even-via-a-rogue-advisor
  (testing "an otherwise-allowed op whose rationale smuggles a finalization/execution action phrase is held"
    (let [st (fresh-store)
          rogue (reify advisor/Advisor
                  (-advise [_ _store _request]
                    {:op :log-trip-record :effect :propose :driver-id "D-1" :depot-id "depot-9"
                     :trip-id "T-1" :fare-amount 12.5 :animal-condition-checkin :serviceable
                     :timestamp "2026-07-14T10:00:00Z"
                     :stake :low :confidence 0.99
                     :rationale "logged the trip in order to finalize the route"}))
          graph (actor/build-graph {:store st :advisor rogue})
          result (actor/run-request! graph {:op :log-trip-record} {} "thread-9")]
      (is (= :hold (:disposition (:state result))))
      (is (empty? (store/records-of st "depot-9"))))))

;; --- escalation / human-in-the-loop --------------------------------------

(deftest interrupts-then-approves-flag-welfare-concern-on-human-approval
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:op :flag-welfare-concern :stake :low :driver-id "D-1" :depot-id "depot-NEW"
                  :reason :animal-welfare :note "new depot intake needs equipment audit"}
        interrupted (actor/run-request! graph request {} "thread-10")]
    (is (= :interrupted (:status interrupted)))
    (is (empty? (store/records-of st "depot-NEW")))
    (let [resumed (actor/approve! graph "thread-10")]
      (is (= :done (:status resumed)))
      (is (= 1 (count (store/records-of st "depot-NEW")))))))

(deftest interrupts-then-approves-above-threshold-maintenance-order-on-human-approval
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:op :coordinate-maintenance-order :stake :low :driver-id "D-1" :depot-id "depot-9"
                  :item "replacement harness sets" :cost 5000 :vendor "CartageSupplyCo"}
        interrupted (actor/run-request! graph request {} "thread-11")]
    (is (= :interrupted (:status interrupted)))
    (let [resumed (actor/approve! graph "thread-11")]
      (is (= :done (:status resumed)))
      (is (some? (get-in resumed [:state :record]))))))
