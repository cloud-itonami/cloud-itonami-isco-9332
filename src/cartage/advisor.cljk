(ns cartage.advisor
  "Dispatch Logistics Advisor — the advisor named in this repository's
  README, proposing a dispatch/logistics-coordination operation (log a
  trip/fare/animal-condition-check-in record, schedule a driver-roster/
  route-assignment operation, flag a vehicle-defect/road-hazard/animal-
  welfare concern for human review, or coordinate a vehicle/harness
  maintenance procurement order) from a driver's intake queue, depot
  roster and maintenance policy. Swappable mock/llm; the advisor ONLY
  proposes — `cartage.governor` checks driver/depot verification and
  scope independently and always escalates welfare-concern flags and
  above-threshold maintenance orders. Modeled on cloud-itonami-isco-5411's
  advisor.

  This advisor NEVER proposes finalizing a route/traffic-navigation
  decision, deciding an animal-welfare/treatment disposition, or
  overriding a driver's on-road or animal-handling safety judgment —
  no such op exists anywhere in the closed allowlist below
  (`cartage.governor/closed-op-allowlist`), and the rationale text this
  advisor emits never uses a finalization/execution phrase for any of
  those actions (`cartage.governor/scope-excluded-terms`), so the
  advisor's own DEFAULT proposals never self-trip the governor's
  scope-exclusion check (see `cartage.governor-test/
  default-mock-advisor-proposals-never-self-trip-scope-exclusion`).
  Any observation suggesting a vehicle defect, road hazard, or animal-
  welfare concern is surfaced ONLY via `:flag-welfare-concern`, which
  always escalates to a human and never auto-commits — the robot's
  role ends at \"here is the trip/roster/condition-check-in status\",
  never \"here is where to drive\" or \"here is whether the animal is
  fit to work\". This actor coordinates dispatch/logistics scheduling
  ONLY — it never operates the vehicle and never directly handles the
  animal.

  A proposal:
  {:op :log-trip-record|:schedule-dispatch-operation|
       :flag-welfare-concern|:coordinate-maintenance-order
   :effect :propose :driver-id str :depot-id (str or nil, only nil for
   :flag-welfare-concern) :stake kw :confidence n :rationale str, plus
   op-specific fields (:trip-id/:fare-amount/:animal-condition-checkin/
   :timestamp for log-trip-record; :route-id/:proposed-time/:vehicle-id
   for schedule-dispatch-operation; :reason/:note for
   flag-welfare-concern; :item/:cost/:vendor for
   coordinate-maintenance-order)}"
  (:require [clojure.edn :as edn]))

(defprotocol Advisor
  (-advise [advisor store request] "request -> proposal map"))

(defn- rationale-for [op depot-id]
  (str "documented " (name op)
       (if depot-id (str " for depot " depot-id) " (no depot yet — new-depot intake)")))

(defn- infer [_store {:keys [op stake driver-id depot-id] :as request}]
  (let [base {:op op
              :effect :propose
              :driver-id driver-id
              :depot-id depot-id
              :stake (or stake :low)
              :confidence (case (or stake :low) :high 0.7 :medium 0.85 :low 0.95)
              :rationale (rationale-for op depot-id)}]
    (merge base
           (case op
             :log-trip-record
             (select-keys request [:trip-id :fare-amount :animal-condition-checkin :timestamp])
             :schedule-dispatch-operation
             (select-keys request [:route-id :proposed-time :vehicle-id])
             :flag-welfare-concern
             (select-keys request [:reason :note])
             :coordinate-maintenance-order
             (select-keys request [:item :cost :vendor])
             {}))))

(defn mock-advisor []
  (reify Advisor
    (-advise [_ store request] (infer store request))))

(def ^:private system-prompt
  "You are a dispatch/logistics coordination advisor for an animal-
   drawn-vehicle dispatch depot. Given a request, propose an :op, the
   :driver-id and (when relevant) :depot-id plus the op's own fields,
   an honest :confidence and a :stake. You are a dispatch/logistics
   scheduling coordination robot ONLY — you help log trip/fare/animal-
   condition-check-in records, schedule driver-roster and route-
   assignment operations, and coordinate vehicle/harness maintenance
   procurement orders. Never propose an op outside the closed four-op
   allowlist (:log-trip-record, :schedule-dispatch-operation,
   :flag-welfare-concern, :coordinate-maintenance-order), and NEVER
   propose finalizing a route/traffic-navigation decision, deciding an
   animal's welfare/treatment disposition, or overriding a driver's
   on-road or animal-handling safety judgment — that authority does
   not exist for you, under any circumstance, at any confidence level,
   in any phase. A :log-trip-record entry is trip/fare/animal-
   condition-check-in metadata only, never a route decision or a
   treatment decision. A :schedule-dispatch-operation proposal is
   driver-roster/route-assignment scheduling logistics only — never a
   live traffic-navigation override or a driver-judgment override. You
   never operate the vehicle and never directly handle the animal.
   Any indication that a vehicle defect, road hazard, or animal-
   welfare concern needs human attention must be surfaced only via
   :flag-welfare-concern, which always requires human review
   regardless of confidence. The governor independently verifies
   driver/depot registration and always escalates welfare-concern
   flags and above-threshold maintenance orders to a human.")

(defn- parse-proposal [content]
  (try
    (let [p (edn/read-string content)]
      (if (map? p)
        (assoc p :effect :propose)
        {:op :unknown :effect :propose :confidence 0.0 :stake :high
         :rationale "unparseable LLM response"}))
    (catch #?(:clj Exception :cljs js/Error) _
      {:op :unknown :effect :propose :confidence 0.0 :stake :high
       :rationale "LLM response parse failure"})))

(defn llm-advisor
  [chat-model model-generate-fn gen-opts]
  (reify Advisor
    (-advise [_ _store request]
      (let [msgs [{:role :system :content system-prompt}
                  {:role :user :content (str "operation request: " (pr-str request))}]
            resp (model-generate-fn chat-model msgs gen-opts)]
        (parse-proposal (:content resp))))))
