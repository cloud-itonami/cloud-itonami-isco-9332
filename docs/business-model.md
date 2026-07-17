# Business Model: Animal-drawn Vehicle Dispatch and Logistics Coordination Practice

## Classification

- Repository: `cloud-itonami-isco-9332`
- ISCO-08: `9332`
- Occupation: Drivers of Animal-drawn Vehicles and Machinery
- Social impact: animal-welfare-integrity, road-safety-continuity,
  dispatch-logistics-transparency

## Customer

- animal-drawn-vehicle dispatch depots / stables
- independent drivers/permit holders

## Offer

- trip/fare/animal-condition-check-in record logging
- driver-roster and route-assignment dispatch scheduling
- vehicle/harness maintenance procurement coordination
- always-escalating welfare-concern flagging channel

## Revenue

- monthly depot retainer
- per-trip logging fee

## Trust Controls

- driver/permit record must be independently verified and registered before
  any action
- no proposal, anywhere in the closed op allowlist, can finalize a
  route/traffic-navigation decision, decide an animal-welfare/treatment
  disposition, or override a driver's on-road or animal-handling safety
  judgment — these are structurally absent, not merely gated
- any vehicle-defect, road-hazard or animal-welfare concern surfaces ONLY via
  `:flag-welfare-concern`, which always escalates to a human
- vehicle/harness maintenance orders above the registered per-depot cost
  ceiling always require human sign-off
- trip, scheduling and maintenance records are auditable, not editable
