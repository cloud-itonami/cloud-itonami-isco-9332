(ns cartage.actor
  "CartageActor — the ISCO-08 9332 drivers-of-animal-drawn-vehicles-and-
  machinery dispatch/logistics coordination actor as a
  `langgraph.graph/state-graph` (ADR-2607121000 / CLAUDE.md Actors
  section). One graph run = one dispatch/logistics operation request
  (intake -> advise -> govern -> decide -> commit/hold, with a
  human-approval interrupt for escalated proposals). No infinite
  internal loop; checkpointed per superstep so an interrupted run can
  resume after human sign-off. Modeled on cloud-itonami-isco-5411's
  firestation.actor.

  ```text
  :intake -> :advise -> :govern -> :decide -+-> :commit            (:ok? true)
                                             +-> :request-approval   (:escalate? true, interrupt-before)
                                             +-> :hold               (:hard? true)
  ```

  The unconditional invariant: the Dispatch Logistics Advisor can never
  directly commit a record the CartageGovernor refuses — every
  commit-record! call is gated behind `:decide` — and NO commit path
  can ever finalize a route/traffic-navigation decision, decide an
  animal-welfare/treatment disposition, or override a driver's on-road
  or animal-handling safety judgment (permanently out of scope,
  structurally absent from the op allowlist, see `cartage.governor`).
  Every observation suggesting a vehicle defect, road hazard, or
  animal-welfare concern needs attention can ONLY reach a human via
  the always-escalating `:flag-welfare-concern` op — the robot's role
  ends at \"here is the trip/roster/condition-check-in status\", never
  \"here is where to drive\" or \"here is whether the animal is fit to
  work\". This actor coordinates dispatch/logistics scheduling ONLY —
  it never operates the vehicle and never directly handles the
  animal."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [cartage.advisor :as advisor]
            [cartage.governor :as governor]
            [cartage.store :as store]))

(defn build-graph
  "Build a compiled CartageActor graph. `store` implements
  `cartage.store/Store`. `advisor` implements
  `cartage.advisor/Advisor` (defaults to `mock-advisor`).
  `checkpointer` defaults to an in-memory one."
  [{:keys [store advisor checkpointer]
    :or {advisor (advisor/mock-advisor)
         checkpointer (cp/mem-checkpointer)}}]
  (-> (g/state-graph
       {:channels
        {:request     {:default nil}
         :context     {:default nil}
         :proposal    {:default nil}
         :verdict     {:default nil}
         :disposition {:default nil}
         :record      {:default nil}
         :audit       {:reducer into :default []}}})
      (g/add-node :intake (fn [s] s))
      (g/add-node :advise
                   (fn [{:keys [request]}]
                     (let [p (advisor/-advise advisor store request)]
                       {:proposal p
                        :audit [{:node :advise :request request :proposal p}]})))
      (g/add-node :govern
                   (fn [{:keys [request context proposal]}]
                     (let [v (governor/check request context proposal store)]
                       {:verdict v
                        :audit [{:node :govern :verdict v}]})))
      (g/add-node :decide
                   (fn [{:keys [verdict]}]
                     {:disposition (cond
                                     (:hard? verdict) :hold
                                     (:escalate? verdict) :request-approval
                                     :else :commit)}))
      (g/add-node :request-approval (fn [s] s))
      (g/add-node :commit
                   (fn [{:keys [proposal]}]
                     (let [record {:driver-id (:driver-id proposal)
                                    :depot-id (:depot-id proposal)
                                    :op (:op proposal)
                                    :payload proposal}]
                       (store/commit-record! store record)
                       (store/append-ledger! store {:disposition :commit :record record})
                       {:record record
                        :audit [{:node :commit :record record}]})))
      (g/add-node :hold
                   (fn [{:keys [verdict]}]
                     (store/append-ledger! store {:disposition :hold :verdict verdict})
                     {:audit [{:node :hold :verdict verdict}]}))
      (g/set-entry-point :intake)
      (g/add-edge :intake :advise)
      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)
      (g/add-conditional-edges
       :decide
       (fn [{:keys [disposition]}]
         (case disposition
           :commit :commit
           :request-approval :request-approval
           :hold)))
      (g/add-edge :request-approval :commit)
      (g/set-finish-point :commit)
      (g/set-finish-point :hold)
      (g/compile-graph {:checkpointer checkpointer
                         :interrupt-before #{:request-approval}})))

(defn run-request!
  "Run one operation request to completion or interrupt. `thread-id`
  scopes checkpointing for resume after human approval."
  [graph request context thread-id]
  (g/run* graph {:request request :context context} {:thread-id thread-id}))

(defn approve!
  "Human-in-the-loop resume: the interrupted `:request-approval` node
  advances straight to `:commit` on resume (approval is the act of
  resuming the thread). This is a human acknowledging that the trip/
  roster/condition-check-in status has been surfaced/coordinated as
  proposed — it is NEVER an approval of a route/traffic-navigation
  decision, an animal-welfare/treatment decision, or an override of
  the driver's on-road or animal-handling safety judgment, since no
  such proposal can ever reach this point (see
  `cartage.governor/closed-op-allowlist`)."
  [graph thread-id]
  (g/run* graph nil {:thread-id thread-id :resume? true}))
