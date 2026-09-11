(ns cartage.store
  "SSoT for the ISCO-08 9332 drivers-of-animal-drawn-vehicles-and-
  machinery dispatch/logistics coordination actor (itonami actor
  pattern, ADR-2607121000 / CLAUDE.md Actors section; README's
  'Robotics premise' — a dispatch-logistics robot performs trip/fare/
  animal-condition-check-in data entry, driver-roster and route-
  assignment scheduling, and vehicle/harness maintenance coordination
  under this advisor/governor pair, which never dispatches hardware
  itself, never operates the vehicle, never directly handles the
  animal, and NEVER exercises, simulates exercising, or proposes
  exercising ANY route/traffic-navigation decision or animal-welfare/
  treatment decision, and never overrides a driver's on-road or
  animal-handling safety judgment — every one of those capabilities is
  a permanently out-of-scope, structurally absent op). Modeled on
  cloud-itonami-isco-5411's firestation.store, itself modeled on
  cloud-itonami-isco-3313's accountingsupport.store.

  Domain:

    driver — a registered animal-drawn-vehicle driver/permit holder
             {:driver-id :name :depot-id :verified? boolean}.
             Independently registered/verified identity, never trusted
             from the proposal alone (\"driver/permit record must be
             independently verified/registered before any action\").
             This actor never determines this driver's on-road
             navigation decisions or animal-handling judgment — it
             only logs, schedules and flags administrative/logistics
             records on the driver's behalf.
    depot  — a registered dispatch depot/stable {:depot-id :name
             :max-maintenance-cost number :verified? boolean}.
             Independently registered/verified, never trusted from the
             proposal alone. `:max-maintenance-cost` is the registered
             per-depot ceiling a proposed `:coordinate-maintenance-
             order` cost above which always escalates to a human — NOT
             a hard block, a maintenance order over budget just needs
             sign-off, it is not itself unsafe.
    record — a committed operating record (trip/fare/animal-condition
             check-in log entry, driver-roster/route-assignment
             scheduling proposal, welfare-concern flag, or vehicle/
             harness maintenance coordination proposal) — written ONLY
             via commit-record!. A committed record is NEVER a route/
             traffic-navigation decision, an animal-welfare/treatment
             decision, or an override of the driver's on-road or
             animal-handling safety judgment — this actor documents
             and coordinates dispatch logistics, it never operates the
             vehicle or directly handles the animal.
    ledger — append-only audit trail, commit or hold."
  )

(defprotocol Store
  (driver [s driver-id])
  (depot [s depot-id])
  (records-of [s depot-id])
  (ledger [s])
  (register-driver! [s d])
  (register-depot! [s dep])
  (commit-record! [s record])
  (append-ledger! [s fact]))

(defrecord MemStore [a]
  Store
  (driver [_ driver-id] (get-in @a [:drivers driver-id]))
  (depot [_ depot-id] (get-in @a [:depots depot-id]))
  (records-of [_ depot-id] (filter #(= depot-id (:depot-id %)) (:records @a)))
  (ledger [_] (:ledger @a))
  (register-driver! [s d]
    (swap! a assoc-in [:drivers (:driver-id d)] d) s)
  (register-depot! [s dep]
    (swap! a assoc-in [:depots (:depot-id dep)] dep) s)
  (commit-record! [s record]
    (swap! a update :records (fnil conj []) record) s)
  (append-ledger! [s fact]
    (swap! a update :ledger (fnil conj []) fact) s))

(defn mem-store
  ([] (mem-store {}))
  ([seed] (->MemStore (atom (merge {:drivers {} :depots {} :records [] :ledger []}
                                   seed)))))
