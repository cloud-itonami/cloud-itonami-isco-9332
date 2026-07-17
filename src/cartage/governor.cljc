(ns cartage.governor
  "CartageGovernor — the independent safety/traceability layer named in
  this repository's README/business-model.md, gating every dispatch/
  logistics-coordination operation an advisor may propose. The
  governor never dispatches hardware itself and NEVER lets a proposal
  exercise, simulate exercising, or propose exercising ANY route/
  traffic-navigation decision, ANY animal-welfare/treatment decision,
  or ANY override of a driver's on-road or animal-handling safety
  judgment — every one of these is permanently out of scope for this
  actor, not merely gated behind escalation. This carries BOTH a
  road-safety dimension (traffic navigation) AND an animal-welfare
  dimension (the animal's treatment/working conditions); this actor
  coordinates DISPATCH/LOGISTICS SCHEDULING ONLY — it never operates
  the vehicle and never directly handles the animal. This mirrors the
  Wave4 person-facing-service safety guardrail (ADR-2607152500):
  decisions directly touching road safety or an animal's welfare
  always exclude the closed op allowlist and always escalate to the
  human driver/dispatcher. Modeled on cloud-itonami-isco-5411's
  firestation.governor, with the same closed proposal-op allowlist +
  content-based scope-exclusion shape, adapted to this vertical's
  route/traffic-navigation and animal-welfare/treatment guardrail.

  HARD invariants (:hard? true, ALWAYS :hold, never overridable):
    1. driver provenance         — the proposing driver/permit-holder
                                record must be independently registered
                                AND verified before ANY proposal can
                                commit or escalate. Never trusts the
                                proposal's own claim of who the driver
                                is.
    2. no-actuation               — proposal :effect must be :propose
                                (the governor never dispatches hardware
                                and never itself operates the vehicle or
                                directly handles the animal; it only
                                gates what the advisor may commit).
    3. closed op allowlist       — the proposal's :op must be one of
                                the four ops this actor is scoped to
                                (`closed-op-allowlist` below). This is
                                the STRUCTURAL guarantee: no op that
                                resembles finalizing a route/traffic-
                                navigation decision, deciding an
                                animal-welfare/treatment disposition,
                                or overriding a driver's on-road or
                                animal-handling safety judgment exists
                                anywhere in this allowlist — such a
                                proposal cannot even reach a check, let
                                alone pass one. Any :op outside the
                                allowlist is a HARD, PERMANENT block.
    4. depot basis                — a proposal for `:log-trip-record`,
                                `:schedule-dispatch-operation` or
                                `:coordinate-maintenance-order` must
                                cite a REGISTERED AND VERIFIED depot
                                matching the driver's own depot
                                (`:unknown-depot` / `:depot-unverified`
                                / `:depot-mismatch`). `:flag-welfare-
                                concern` does NOT require an existing
                                depot (it is the channel by which a
                                brand-new depot, or an urgent welfare
                                concern with no depot on file yet, is
                                surfaced for human intake).
    5. trip-record decision forbidden — `:log-trip-record` is a trip/
                                fare/animal-condition-check-in metadata
                                record ONLY (trip id, fare amount,
                                animal condition check-in, timestamp).
                                Any proposal carrying a route/traffic-
                                navigation-decision or animal-welfare/
                                treatment-decision field
                                (`log-record-forbidden-keys` below —
                                e.g. `:route-decision`, `:treatment-
                                decision`, `:fitness-for-work-
                                decision`) is a HARD, PERMANENT block —
                                this actor never records a route
                                decision or a treatment decision, only
                                metadata check-ins.
    6. dispatch-schedule override forbidden — `:schedule-dispatch-
                                operation` is driver-roster/route-
                                assignment scheduling logistics ONLY.
                                Any proposal carrying a live traffic-
                                navigation-override or driver-judgment-
                                override field (`schedule-forbidden-
                                keys` below — e.g. `:route-navigation-
                                directive`, `:on-road-judgment-
                                override`, `:animal-handling-judgment-
                                override`) is a HARD, PERMANENT block —
                                this actor never overrides a driver's
                                real-time on-road or animal-handling
                                judgment, it only schedules which
                                driver/vehicle is assigned to which
                                route slot in advance.
    7. scope exclusion            — independent, DEFENSE-IN-DEPTH layer
                                on top of #3/#5/#6: even for an
                                otherwise-allowed op, any proposal whose
                                free text (`:rationale` or `:note`)
                                names a finalization/execution ACTION
                                for a route/traffic-navigation decision,
                                an animal-welfare/treatment decision, or
                                an override of the driver's on-road or
                                animal-handling safety judgment
                                (`scope-excluded-terms` below) is a
                                HARD, PERMANENT block, evaluated
                                unconditionally on content. This actor
                                never exercises route, traffic,
                                treatment, or driver-override authority
                                — it only documents trip/roster records
                                and coordinates dispatch logistics.
  ESCALATION invariants (:escalate? true, ALWAYS human sign-off — these
  are :high/:safety-critical regardless of confidence):
    8. :op :flag-welfare-concern (surfacing a vehicle-defect, road-
                                hazard, or animal-welfare concern that
                                needs human review — ALWAYS requires
                                human review; it is never auto-resolved
                                and never appears in any phase's
                                auto-commit set; this is the ONLY path
                                by which such a concern may be
                                surfaced, and the robot's role ends at
                                \"here is the trip/roster/condition-
                                check-in status\" — never \"here is
                                where to drive\" or \"here is whether
                                the animal is fit to work\").
    9. an above-threshold :coordinate-maintenance-order (vehicle/
                                harness maintenance procurement above
                                `maintenance-cost-escalation-threshold`
                                always needs human sign-off, regardless
                                of confidence — this is an escalation,
                                NOT a hard block, since an over-budget
                                maintenance order is not itself
                                unsafe).
    10. low confidence (< `confidence-floor`)."
  (:require [clojure.string :as str]
            [cartage.store :as store]))

(def confidence-floor 0.6)

;; Vehicle/harness maintenance orders at or below this estimated cost
;; may be auto-commit-eligible (subject to confidence); above it,
;; ALWAYS escalates to a human regardless of confidence.
(def maintenance-cost-escalation-threshold 800)

;; The closed proposal-op allowlist. This governor NEVER allows any op
;; outside this set to commit or even escalate — an op outside this
;; set is a HARD, permanent block (see `hard-violations`
;; :op-not-allowed below), not merely un-auto-committable. This is a
;; dispatch/logistics coordination robot ONLY: it has NO op, anywhere
;; in this allowlist, that resembles finalizing a route/traffic-
;; navigation decision, deciding an animal-welfare/treatment
;; disposition, or overriding a driver's on-road or animal-handling
;; safety judgment. Those capabilities are structurally absent, not
;; gated.
(def closed-op-allowlist
  #{:log-trip-record :schedule-dispatch-operation
    :flag-welfare-concern :coordinate-maintenance-order})

;; :flag-welfare-concern always escalates to a human — never
;; auto-commit-eligible at any phase. It is the ONLY channel through
;; which a vehicle-defect/road-hazard/animal-welfare concern may be
;; surfaced.
(def ^:private always-escalate-ops #{:flag-welfare-concern})

;; Ops that outside observers might expect an "animal-drawn-vehicle
;; dispatch actor" to have — named here explicitly (in addition to the
;; closed-allowlist check above) so the exclusion reads as an
;; intentional, documented scope boundary rather than an incidental
;; unknown op. None of these are ever defined as a real op anywhere in
;; this codebase; they exist ONLY as negative-test fixtures proving
;; `closed-op-allowlist` rejects them.
(def scope-excluded-ops
  #{:finalize-route :override-route-decision :commit-to-route
    :determine-right-of-way :direct-traffic-navigation
    :override-driver-route-judgment :override-driver-on-road-judgment
    :decide-animal-fitness-for-work :determine-animal-fitness-for-work
    :authorize-veterinary-treatment :order-veterinary-treatment
    :override-driver-animal-handling-judgment
    :override-driver-safety-judgment})

;; log-trip-record is a trip/fare/animal-condition-check-in metadata
;; record ONLY. A proposal carrying any of these keys is smuggling a
;; route/traffic-navigation decision or an animal-welfare/treatment
;; decision into what must remain a pure metadata check-in.
(def log-record-forbidden-keys
  #{:route-decision :route-finalization :navigation-override
    :right-of-way-decision :traffic-priority-decision :treatment-decision
    :veterinary-treatment-order :fitness-for-work-decision :welfare-disposition})

;; schedule-dispatch-operation is driver-roster/route-ASSIGNMENT
;; scheduling logistics ONLY (deciding in advance which driver/vehicle
;; is assigned to which route slot). A proposal carrying any of these
;; keys is smuggling a live traffic-navigation override or an override
;; of the driver's real-time on-road/animal-handling judgment into
;; what must remain pure advance scheduling.
(def schedule-forbidden-keys
  #{:route-navigation-directive :traffic-priority-override
    :right-of-way-assignment :on-road-judgment-override
    :animal-handling-judgment-override :navigation-command
    :treatment-decision :fitness-for-work-decision})

;; Scope-exclusion terms, phrased as the FINALIZATION/EXECUTION ACTION
;; (never a bare noun like "route" or "welfare" alone) — a known
;; self-tripping bug class in this fleet: a bare-noun term list can
;; accidentally match inside the mock advisor's own default rationale
;; text for a legitimate, allowed proposal (this actor's own op is
;; literally named `:flag-welfare-concern`, so a bare "welfare" term
;; would self-trip on every single legitimate welfare-flag proposal),
;; causing the actor to self-block on its own happy path. This
;; advisor's default rationale template is "documented <op> for depot
;; <id>", which never contains any of these full action phrases. See
;; `cartage.governor-test/
;; default-mock-advisor-proposals-never-self-trip-scope-exclusion`.
(def scope-excluded-terms
  ["finalized the route" "finalize the route"
   "committed to the route" "commit to the route"
   "overrode the driver's route judgment" "override the driver's route judgment"
   "overrode the driver's on-road judgment" "override the driver's on-road judgment"
   "overrode the driver's on-road safety judgment" "override the driver's on-road safety judgment"
   "overrode the driver's animal-handling judgment" "override the driver's animal-handling judgment"
   "overrode the driver's safety judgment" "override the driver's safety judgment"
   "determined the animal's fitness for work" "determine the animal's fitness for work"
   "decided the animal was fit for work" "decide the animal was fit for work"
   "decided the animal's fitness for work" "decide the animal's fitness for work"
   "authorized the veterinary treatment" "authorize the veterinary treatment"
   "ordered the veterinary treatment" "order the veterinary treatment"
   "administered veterinary treatment" "administer veterinary treatment"
   "directed the traffic navigation" "direct the traffic navigation"
   "finalized the traffic-navigation decision" "finalize the traffic-navigation decision"
   "assigned the right of way" "assign the right of way"
   "made the right-of-way decision" "make the right-of-way decision"
   "ルートを確定した" "経路判断を上書きした" "運転手の路上判断を上書きした"
   "運転手の動物取扱判断を上書きした" "動物の就労適性を判定した" "獣医treatment を指示した"
   "交通誘導を指示した" "優先通行権を決定した"])

(defn out-of-scope?
  "True if any free-text field on `proposal` (:rationale or :note)
  contains a scope-excluded finalization/execution phrase for a
  route/traffic-navigation decision, an animal-welfare/treatment
  decision, or an override of a driver's on-road or animal-handling
  safety judgment."
  [proposal]
  (let [text (str (:rationale proposal) " " (:note proposal))]
    (boolean (some #(str/includes? text %) scope-excluded-terms))))

(defn- forbidden-keys-present [proposal forbidden-keys]
  (seq (filter #(contains? proposal %) forbidden-keys)))

(def ^:private depot-required-ops
  #{:log-trip-record :schedule-dispatch-operation :coordinate-maintenance-order})

(defn- hard-violations [{:keys [proposal]} driver-record depot-record]
  (let [{:keys [op depot-id]} proposal
        needs-depot? (contains? depot-required-ops op)]
    (cond-> []
      (not= :propose (:effect proposal))
      (conj {:rule :no-actuation :detail "effect は :propose のみ許可（governor は経路判断/動物の治療判断を直接実行しない）"})

      (not (contains? closed-op-allowlist op))
      (conj {:rule :op-not-allowed
             :detail "closed allowlist 外の op（経路/交通誘導判断・動物福祉/治療判断・運転手の安全判断の上書きを含む一切の確定は許可されない）"})

      (nil? driver-record)
      (conj {:rule :unknown-driver :detail "未登録 driver への提案は不可"})

      (and driver-record (not (:verified? driver-record)))
      (conj {:rule :driver-unverified :detail "未検証 driver への提案は不可（登録のみでは不十分）"})

      (and needs-depot? (nil? depot-id))
      (conj {:rule :missing-depot-id :detail "この op には depot-id が必須"})

      (and needs-depot? depot-id (nil? depot-record))
      (conj {:rule :unknown-depot :detail "未登録 depot への提案は不可"})

      (and needs-depot? depot-record (not (:verified? depot-record)))
      (conj {:rule :depot-unverified :detail "未検証 depot への提案は不可（登録のみでは不十分）"})

      (and needs-depot? depot-record driver-record
           (not= (:depot-id depot-record) (:depot-id driver-record)))
      (conj {:rule :depot-mismatch :detail "depot が driver の所属と別 depot のもの"})

      (and (= :log-trip-record op) (seq (forbidden-keys-present proposal log-record-forbidden-keys)))
      (conj {:rule :trip-record-decision-forbidden
             :detail "log-trip-record は trip/fare/動物状態チェックインのメタデータ記録のみ — 経路判断・治療判断の記録は永久に禁止"})

      (and (= :schedule-dispatch-operation op) (seq (forbidden-keys-present proposal schedule-forbidden-keys)))
      (conj {:rule :dispatch-schedule-override-forbidden
             :detail "schedule-dispatch-operation は事前の配車/経路割当スケジューリングのみ — リアルタイムの交通誘導・運転手判断の上書きは永久に禁止"})

      (out-of-scope? proposal)
      (conj {:rule :scope-excluded
             :detail "経路/交通誘導判断・動物福祉/治療判断・運転手の路上/動物取扱安全判断の上書きを直接確定する提案は恒久的に許可されない（このactorは文書化とディスパッチ/ロジスティクス調整のみを行う）"}))))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a
  `store` implementing `cartage.store/Store`. Pure — never mutates the
  store, never finalizes a route/traffic-navigation decision, never
  decides an animal-welfare/treatment disposition, never overrides a
  driver's on-road or animal-handling safety judgment."
  [_request _context proposal store]
  (let [driver-record (some->> (:driver-id proposal) (store/driver store))
        depot-record (some->> (:depot-id proposal) (store/depot store))
        hard (hard-violations {:proposal proposal} driver-record depot-record)
        hard? (boolean (seq hard))
        conf (or (:confidence proposal) 0.0)
        low? (< conf confidence-floor)
        always-risky? (contains? always-escalate-ops (:op proposal))
        over-threshold-maintenance-order?
        (and (= :coordinate-maintenance-order (:op proposal))
             (number? (:cost proposal))
             (> (:cost proposal) maintenance-cost-escalation-threshold))]
    {:ok? (and (not hard?) (not low?) (not always-risky?) (not over-threshold-maintenance-order?))
     :violations hard
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (or low? always-risky? over-threshold-maintenance-order?))}))
