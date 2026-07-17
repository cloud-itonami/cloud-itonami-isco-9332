(ns cartage.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [cartage.store :as store]
            [cartage.advisor :as advisor]
            [cartage.governor :as governor]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-driver! st {:driver-id "D-1" :name "Driver Kobo"
                                :depot-id "depot-9" :verified? true})
    (store/register-depot! st {:depot-id "depot-9"
                               :max-maintenance-cost 800 :verified? true})
    st))

(defn- log-op []
  {:op :log-trip-record :effect :propose :driver-id "D-1" :depot-id "depot-9"
   :trip-id "T-1" :fare-amount 12.5 :animal-condition-checkin :serviceable
   :timestamp "2026-07-14T10:00:00Z" :stake :low :confidence 0.9
   :rationale "documented log-trip-record for depot depot-9"})

(defn- schedule-op []
  {:op :schedule-dispatch-operation :effect :propose :driver-id "D-1" :depot-id "depot-9"
   :route-id "R-12" :proposed-time "2026-07-20T09:00:00Z"
   :vehicle-id "cart-4" :stake :low :confidence 0.9
   :rationale "documented schedule-dispatch-operation for depot depot-9"})

(defn- flag-op
  ([] (flag-op nil))
  ([depot-id]
   {:op :flag-welfare-concern :effect :propose :driver-id "D-1" :depot-id depot-id
    :reason :animal-welfare :note "animal showing signs of fatigue, recommend depot review"
    :stake :low :confidence 0.9
    :rationale "documented flag-welfare-concern for depot (no depot yet — new-depot intake)"}))

(defn- maintenance-op [cost]
  {:op :coordinate-maintenance-order :effect :propose :driver-id "D-1" :depot-id "depot-9"
   :item "harness replacement" :cost cost :vendor "CartageSupplyCo" :stake :low :confidence 0.9
   :rationale "documented coordinate-maintenance-order for depot depot-9"})

(def ^:private req {})

;; --- happy path -----------------------------------------------------

(deftest ok-well-formed-log-entry
  (let [st (fresh-store)
        v (governor/check req {} (log-op) st)]
    (is (:ok? v))
    (is (not (:hard? v)))
    (is (not (:escalate? v)))))

(deftest ok-well-formed-dispatch-scheduling
  (let [st (fresh-store)
        v (governor/check req {} (schedule-op) st)]
    (is (:ok? v))))

(deftest ok-at-or-below-threshold-maintenance-order
  (let [st (fresh-store)
        v (governor/check req {} (maintenance-op 400) st)]
    (is (:ok? v))))

(deftest ok-at-exact-maintenance-cost-threshold-boundary
  (testing "the maintenance-cost escalation threshold is inclusive (exactly-at-threshold does not escalate)"
    (let [st (fresh-store)
          v (governor/check req {} (maintenance-op governor/maintenance-cost-escalation-threshold) st)]
      (is (:ok? v))
      (is (not (:escalate? v))))))

;; --- driver provenance ----------------------------------------------

(deftest hard-on-unregistered-driver
  (let [st (fresh-store)
        v (governor/check req {} (assoc (log-op) :driver-id "ghost") st)]
    (is (:hard? v))
    (is (some #(= :unknown-driver (:rule %)) (:violations v)))))

(deftest hard-on-unverified-driver
  (let [st (fresh-store)]
    (store/register-driver! st {:driver-id "D-2" :name "Unverified"
                                :depot-id "depot-9" :verified? false})
    (let [v (governor/check req {} (assoc (log-op) :driver-id "D-2") st)]
      (is (:hard? v))
      (is (some #(= :driver-unverified (:rule %)) (:violations v))))))

;; --- depot provenance ---------------------------------------------

(deftest hard-on-missing-depot-id
  (let [st (fresh-store)
        v (governor/check req {} (assoc (log-op) :depot-id nil) st)]
    (is (:hard? v))
    (is (some #(= :missing-depot-id (:rule %)) (:violations v)))))

(deftest hard-on-unknown-depot
  (let [st (fresh-store)
        v (governor/check req {} (assoc (log-op) :depot-id "depot-ghost") st)]
    (is (:hard? v))
    (is (some #(= :unknown-depot (:rule %)) (:violations v)))))

(deftest hard-on-unverified-depot
  (let [st (fresh-store)]
    (store/register-depot! st {:depot-id "depot-2" :max-maintenance-cost 800 :verified? false})
    (let [v (governor/check req {} (assoc (log-op) :depot-id "depot-2") st)]
      (is (:hard? v))
      (is (some #(= :depot-unverified (:rule %)) (:violations v))))))

(deftest hard-on-depot-mismatch
  (let [st (fresh-store)]
    (store/register-depot! st {:depot-id "depot-3" :max-maintenance-cost 800 :verified? true})
    (let [v (governor/check req {} (assoc (log-op) :depot-id "depot-3") st)]
      (is (:hard? v))
      (is (some #(= :depot-mismatch (:rule %)) (:violations v))))))

(deftest flag-welfare-concern-does-not-require-existing-depot
  (testing "flag-welfare-concern is the channel by which a brand-new depot, or an urgent concern with no depot on
            file yet, is surfaced for human intake"
    (let [st (fresh-store)
          v (governor/check req {} (flag-op nil) st)]
      (is (not (:hard? v)))
      (is (:escalate? v)))))

;; --- no-actuation / closed allowlist ----------------------------------

(deftest hard-on-no-actuation-violation
  (let [st (fresh-store)
        v (governor/check req {} (assoc (log-op) :effect :direct-write) st)]
    (is (:hard? v))
    (is (some #(= :no-actuation (:rule %)) (:violations v)))))

(deftest hard-on-op-not-allowed-finalize-route
  (testing "no path through this actor can finalize a route/traffic-navigation decision — no such op exists in the
            allowlist to begin with; this asserts the governor also rejects one forged onto a proposal"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (log-op) :op :finalize-route) st)]
      (is (:hard? v))
      (is (some #(= :op-not-allowed (:rule %)) (:violations v))))))

(deftest hard-on-op-not-allowed-determine-animal-fitness-for-work
  (let [st (fresh-store)
        v (governor/check req {} (assoc (log-op) :op :determine-animal-fitness-for-work) st)]
    (is (:hard? v))
    (is (some #(= :op-not-allowed (:rule %)) (:violations v)))))

(deftest hard-on-op-not-allowed-override-driver-route-judgment
  (let [st (fresh-store)
        v (governor/check req {} (assoc (log-op) :op :override-driver-route-judgment) st)]
    (is (:hard? v))
    (is (some #(= :op-not-allowed (:rule %)) (:violations v)))))

(deftest hard-on-op-not-allowed-authorize-veterinary-treatment
  (let [st (fresh-store)
        v (governor/check req {} (assoc (log-op) :op :authorize-veterinary-treatment) st)]
    (is (:hard? v))
    (is (some #(= :op-not-allowed (:rule %)) (:violations v)))))

(deftest hard-on-op-not-allowed-determine-right-of-way
  (let [st (fresh-store)
        v (governor/check req {} (assoc (log-op) :op :determine-right-of-way) st)]
    (is (:hard? v))
    (is (some #(= :op-not-allowed (:rule %)) (:violations v)))))

(deftest every-scope-excluded-op-name-is-rejected
  (testing "every explicitly named scope-excluded op fixture is a hard, permanent block"
    (let [st (fresh-store)]
      (doseq [op governor/scope-excluded-ops]
        (let [v (governor/check req {} (assoc (log-op) :op op) st)]
          (is (:hard? v) (str "op " op " was not hard-blocked"))
          (is (some #(= :op-not-allowed (:rule %)) (:violations v))
              (str "op " op " did not trip :op-not-allowed")))))))

;; --- trip-record decision / dispatch-schedule override forbidden -------

(deftest hard-on-trip-record-decision-forbidden-route-decision-key
  (testing "log-trip-record is a physical trip/fare/animal-condition-check-in metadata record only — route
            decisions are forbidden"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (log-op) :route-decision "take route R-9") st)]
      (is (:hard? v))
      (is (some #(= :trip-record-decision-forbidden (:rule %)) (:violations v))))))

(deftest hard-on-trip-record-decision-forbidden-treatment-decision-key
  (let [st (fresh-store)
        v (governor/check req {} (assoc (log-op) :treatment-decision "administer rest day") st)]
    (is (:hard? v))
    (is (some #(= :trip-record-decision-forbidden (:rule %)) (:violations v)))))

(deftest hard-on-trip-record-decision-forbidden-fitness-for-work-key
  (let [st (fresh-store)
        v (governor/check req {} (assoc (log-op) :fitness-for-work-decision :fit) st)]
    (is (:hard? v))
    (is (some #(= :trip-record-decision-forbidden (:rule %)) (:violations v)))))

(deftest hard-on-dispatch-schedule-override-forbidden-navigation-directive-key
  (testing "schedule-dispatch-operation never carries a live traffic-navigation override"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (schedule-op) :route-navigation-directive "turn left at Main St") st)]
      (is (:hard? v))
      (is (some #(= :dispatch-schedule-override-forbidden (:rule %)) (:violations v))))))

(deftest hard-on-dispatch-schedule-override-forbidden-on-road-judgment-key
  (let [st (fresh-store)
        v (governor/check req {} (assoc (schedule-op) :on-road-judgment-override "proceed despite driver hesitation") st)]
    (is (:hard? v))
    (is (some #(= :dispatch-schedule-override-forbidden (:rule %)) (:violations v)))))

(deftest hard-on-dispatch-schedule-override-forbidden-animal-handling-judgment-key
  (let [st (fresh-store)
        v (governor/check req {} (assoc (schedule-op) :animal-handling-judgment-override "push through fatigue") st)]
    (is (:hard? v))
    (is (some #(= :dispatch-schedule-override-forbidden (:rule %)) (:violations v)))))

;; --- scope-excluded rationale (defense-in-depth) -----------------------

(deftest hard-on-scope-excluded-route-finalization-rationale
  (testing "a proposal on an otherwise-allowed op whose rationale names a finalization action for a route decision
            is a permanent HARD block, independent of the op-allowlist check"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (log-op) :rationale "logged the trip in order to finalize the route") st)]
      (is (:hard? v))
      (is (some #(= :scope-excluded (:rule %)) (:violations v))))))

(deftest hard-on-scope-excluded-driver-judgment-override-rationale
  (let [st (fresh-store)
        v (governor/check req {} (assoc (log-op) :rationale "logged the trip to override the driver's route judgment") st)]
    (is (:hard? v))
    (is (some #(= :scope-excluded (:rule %)) (:violations v)))))

(deftest hard-on-scope-excluded-animal-fitness-rationale
  (let [st (fresh-store)
        v (governor/check req {} (assoc (log-op) :rationale "logged the check-in to determine the animal's fitness for work") st)]
    (is (:hard? v))
    (is (some #(= :scope-excluded (:rule %)) (:violations v)))))

(deftest hard-on-scope-excluded-veterinary-treatment-rationale
  (let [st (fresh-store)
        v (governor/check req {} (assoc (log-op) :rationale "logged the check-in to order the veterinary treatment") st)]
    (is (:hard? v))
    (is (some #(= :scope-excluded (:rule %)) (:violations v)))))

(deftest hard-on-scope-excluded-note-field
  (testing "the scope-exclusion check also inspects :note (used by flag-welfare-concern)"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (flag-op "depot-9") :note "recommend we finalize the route now") st)]
      (is (:hard? v))
      (is (some #(= :scope-excluded (:rule %)) (:violations v))))))

;; --- escalation ---------------------------------------------------------

(deftest always-escalates-flag-welfare-concern-even-at-high-confidence
  (testing "surfacing a welfare concern always requires human review"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (flag-op "depot-9") :confidence 0.99) st)]
      (is (not (:hard? v)))
      (is (:escalate? v)))))

(deftest always-escalates-above-threshold-maintenance-order
  (testing "a vehicle/harness maintenance order above the cost threshold always needs human sign-off"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (maintenance-op (+ governor/maintenance-cost-escalation-threshold 1))
                                          :confidence 0.99)
                            st)]
      (is (not (:hard? v)))
      (is (:escalate? v)))))

(deftest escalates-low-confidence
  (let [st (fresh-store)
        v (governor/check req {} (assoc (log-op) :confidence 0.3) st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))

;; --- fleet-known self-trip regression -----------------------------------

(deftest default-mock-advisor-proposals-never-self-trip-scope-exclusion
  (testing "the default mock advisor's own rationale text for every op in the closed allowlist never contains a
            scope-excluded finalization/execution phrase for a route/traffic-navigation decision, an animal-
            welfare/treatment decision, or an override of the driver's on-road or animal-handling safety judgment
            -- crucially, :flag-welfare-concern's own op name contains the bare noun \"welfare\", so this asserts
            the term list is phrased as full finalization/execution actions rather than bare nouns"
    (let [st (fresh-store)
          adv (advisor/mock-advisor)
          requests [{:op :log-trip-record :driver-id "D-1" :depot-id "depot-9" :stake :low
                     :trip-id "T-1" :fare-amount 12.5 :animal-condition-checkin :serviceable
                     :timestamp "2026-07-14T10:00:00Z"}
                    {:op :schedule-dispatch-operation :driver-id "D-1" :depot-id "depot-9" :stake :low
                     :route-id "R-12" :proposed-time "2026-07-20T09:00:00Z" :vehicle-id "cart-4"}
                    {:op :flag-welfare-concern :driver-id "D-1" :depot-id "depot-9" :stake :low
                     :reason :animal-welfare :note "animal showing signs of fatigue, recommend depot review"}
                    {:op :flag-welfare-concern :driver-id "D-1" :depot-id nil :stake :low
                     :reason :vehicle-defect :note "new depot intake needs equipment audit"}
                    {:op :coordinate-maintenance-order :driver-id "D-1" :depot-id "depot-9" :stake :low
                     :item "harness replacement" :cost 40 :vendor "CartageSupplyCo"}]]
      (doseq [req' requests]
        (let [proposal (advisor/-advise adv st req')]
          (is (false? (governor/out-of-scope? proposal))
              (str "op " (:op req') " self-tripped scope-exclusion: " (:rationale proposal)))
          (let [v (governor/check {} {} proposal st)]
            (is (not (contains? (set (map :rule (:violations v))) :scope-excluded))
                (str "op " (:op req') " tripped :scope-excluded in governor/check"))
            (is (not (contains? (set (map :rule (:violations v))) :op-not-allowed))
                (str "op " (:op req') " tripped :op-not-allowed in governor/check"))))))))
