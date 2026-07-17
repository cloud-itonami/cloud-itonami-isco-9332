# Governance

`cloud-itonami-isco-9332` is an OSS open-occupation blueprint. Governance covers
both code and the operator model.

## Maintainers

Maintainers may merge changes that preserve these invariants:

- the Advisor cannot directly dispatch robot actions, operate the vehicle,
  directly handle the animal, or disclose records.
- CartageGovernor remains independent of the advisor.
- hard policy violations cannot be overridden by human approval.
- the closed op allowlist never gains an op that finalizes a route/traffic-
  navigation decision, decides an animal-welfare/treatment disposition, or
  overrides a driver's on-road or animal-handling safety judgment.
- every commit, hold and approval path is auditable.
- real driver/animal/vehicle/trip/operator data stays outside Git.

## Decision Records

Architecture decisions live in `docs/adr/`. Changes to the trust model,
storage contract, public business model, operator certification or license
should add or update an ADR.

## Operator Governance

Anyone may fork and operate independently. itonami.cloud certification is a
separate trust mark and should require security, audit, support and data-flow
review.

Certified operators can lose certification for:

- bypassing policy checks
- mishandling driver/animal/vehicle/trip/operator data
- misrepresenting certification status
- failing to respond to security incidents
- hiding material changes to customer-facing operation
- attempting to route a route/traffic-navigation decision, an animal-
  welfare/treatment decision, or a driver-judgment override through this
  actor by any means
