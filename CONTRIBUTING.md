# Contributing

`cloud-itonami-isco-9332` accepts contributions to the OSS actor, policy tests,
documentation, examples and open occupation blueprint.

## Development

```bash
kbb -M:dev:test
kbb -M:lint
```

Keep changes small and include tests for policy, audit, store or disclosure
behavior.

## Rules

- Do not commit real driver, animal, vehicle, trip or operator data.
- Keep production writes and disclosures behind CartageGovernor.
- Never add an op, anywhere in the closed allowlist, that finalizes a
  route/traffic-navigation decision, decides an animal-welfare/treatment
  disposition, or overrides a driver's on-road or animal-handling safety
  judgment — this boundary is permanent, not a default to be relaxed later.
- Treat this occupation's workflows as high-risk: add tests for permission,
  purpose, road-safety, animal-welfare and audit logging.
- Document any new business-model or operator assumption in `docs/`.

## Pull Requests

PRs should describe:

- what behavior changed
- which policy invariant is affected
- how it was tested
- whether operator or certification docs need updates
