# Operator Guide

## First Deployment

1. Define the depot's service area and driver-intake process.
2. Register and verify each driver/permit holder and depot before enabling
   any proposal for them.
3. Run synthetic operating cases (trip logging, dispatch scheduling,
   maintenance coordination, welfare-concern flagging).
4. Enable human-reviewed sign-off for `:high`/`:safety-critical` actions —
   this includes every `:flag-welfare-concern` and every above-threshold
   `:coordinate-maintenance-order`, with no exception.
5. Measure operating outcomes and audit coverage.

## Minimum Production Controls

- driver/permit and depot provenance log (registered AND verified before any
  action)
- safety-critical escalation path for welfare/vehicle-defect/road-hazard
  concerns
- provenance for all operating records
- human review for high-risk cases
- audit export for all gated actions

## Certification

Certified operators must prove that CartageGovernor gates every
safety-critical robot action, that safety-critical risks escalate to
humans, and that no configuration or fork can introduce an op that
finalizes a route/traffic-navigation decision, decides an animal-welfare/
treatment disposition, or overrides a driver's on-road or animal-handling
safety judgment.
