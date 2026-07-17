# cloud-itonami-isco-9332

Open Occupation Blueprint for **ISCO-08 9332**: Drivers of Animal-drawn
Vehicles and Machinery.

This repository designs a forkable OSS business for an animal-drawn-
vehicle dispatch and logistics coordination practice: a dispatch-
logistics robot manages trip/fare/animal-condition-check-in records,
driver-roster and route-assignment scheduling, and vehicle/harness
maintenance coordination under a governor-gated actor — and
structurally **never** finalizes a route/traffic-navigation decision,
decides an animal's welfare/treatment disposition, or overrides a
driver's on-road or animal-handling safety judgment.

## This actor has no route/traffic-navigation, animal-welfare/treatment, or driver-override authority

Drivers of animal-drawn vehicles work directly with draft animals on
public roads — this carries BOTH a road-safety dimension (traffic
navigation) AND an animal-welfare dimension (the animal's treatment/
working conditions). **This actor is a dispatch/logistics coordination
robot ONLY.** It never operates the vehicle and never directly handles
the animal. It has NO op, anywhere in its allowlist, that resembles
finalizing a route/traffic-navigation decision, deciding an animal's
welfare/treatment disposition, or overriding a driver's on-road or
animal-handling safety judgment. These are **structurally absent from
the closed op-allowlist entirely**, not merely gated behind
escalation — under any circumstance, at any confidence level, in any
phase. Any observation the robot logs that suggests a vehicle defect,
road hazard, or animal-welfare concern is surfaced ONLY via an
always-escalating `:flag-welfare-concern` op that a human reviews and
acts on entirely themselves. This mirrors the Wave4 person-facing-
service safety guardrail (ADR-2607152500): decisions directly touching
road safety or an animal's welfare always exclude the closed op
allowlist and always escalate. This actor's role ends at "here is the
trip/roster/condition-check-in status" — it has zero authority over
route, traffic, or animal-treatment decisions, which remain entirely
with the human driver/dispatcher, at all times, with zero exception.

**Maturity: `:implemented`.** `src/cartage/` implements the
`CartageActor` as a `langgraph.graph/state-graph` (`cartage.actor`)
wired to a `Dispatch Logistics Advisor` (`cartage.advisor`) and an
independent `CartageGovernor` (`cartage.governor`), following the
itonami actor pattern (ADR-2607121000): `:intake -> :advise -> :govern
-> :decide -+-> :commit (:ok?) +-> :request-approval (:escalate?,
human-in-the-loop interrupt) +-> :hold (:hard?)`. Run `clojure -M:test`
for the current test count.

HARD invariants (always hold, never overridable): driver provenance (a
proposal must resolve to an independently registered AND verified
driver/permit-holder record), a closed four-op proposal allowlist (any
op outside it — including anything that would finalize a route/
traffic-navigation decision, decide an animal-welfare/treatment
disposition, or override a driver's on-road or animal-handling safety
judgment — is a permanent HARD block, because no such op exists in the
allowlist to begin with), no-actuation (`:effect` must be `:propose`),
a registered-and-verified depot basis (for the three ops that
reference one), a trip-record-decision-forbidden check
(`:log-trip-record` may only carry trip/fare/animal-condition-check-in
metadata, never a route decision or a treatment decision), a
dispatch-schedule-override-forbidden check
(`:schedule-dispatch-operation` may only carry driver-roster/route-
assignment scheduling logistics, never a live traffic-navigation
override or a driver-judgment override), and a content-based scope-
exclusion check: any proposal whose free text names a finalization/
execution action for a route/traffic-navigation decision, an animal-
welfare/treatment decision, or an override of the driver's on-road or
animal-handling safety judgment is a permanent HARD block, independent
of and in addition to the op-allowlist check. This actor **never**
exercises, simulates exercising, or proposes exercising any route/
traffic-navigation decision, any animal-welfare/treatment decision, or
any override of a driver's on-road or animal-handling safety judgment
— it only documents trip/roster records and coordinates dispatch
logistics.

Always-escalate (human sign-off regardless of confidence, mapping this
repo's Trust Controls in
[`docs/business-model.md`](docs/business-model.md)):
`:flag-welfare-concern` (surfacing a vehicle-defect, road-hazard, or
animal-welfare concern that needs human review — always requires human
review; never auto-resolved, never in any phase's auto-commit set —
this is the ONLY channel by which such a concern may be surfaced) and
any `:coordinate-maintenance-order` above the registered per-depot
cost threshold.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical/administrative domain work**. Here a dispatch-
logistics robot performs trip/fare/animal-condition-check-in data
entry, driver-roster and route-assignment scheduling, and vehicle/
harness maintenance coordination under an actor that proposes actions
and an independent **CartageGovernor** that gates them. The governor
never dispatches hardware itself; `:high`/`:safety-critical` actions
(such as flagging a welfare concern, or an above-threshold maintenance
order) require human sign-off — and no action in this actor's closed
op allowlist can ever finalize a route/traffic-navigation decision,
decide an animal-welfare/treatment disposition, or override a driver's
on-road or animal-handling safety judgment. This actor coordinates
DISPATCH/LOGISTICS SCHEDULING ONLY — it never operates the vehicle and
never directly handles the animal.

## Core Contract

```text
driver intake queue + depot roster directory + maintenance policy
        |
        v
Dispatch Logistics Advisor -> CartageGovernor -> log record/coordinate, or human sign-off
        |
        v
robot actions (gated) + operating records + audit ledger
```

No automated advice can dispatch a robot action the governor refuses,
finalize a route/traffic-navigation decision, decide an animal-
welfare/treatment disposition, override a driver's on-road or animal-
handling safety judgment, suppress an operating record, or disclose
sensitive data without governor approval and audit evidence.

## Capability layer

Resolves via [`kotoba-lang/occupation`](https://github.com/kotoba-lang/occupation)
(ISCO-08 `9332`). Required capabilities:

- :robotics
- :identity
- :audit-ledger

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## License

AGPL-3.0-or-later.
