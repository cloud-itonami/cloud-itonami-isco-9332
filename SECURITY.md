# Security Policy

This project handles drivers-of-animal-drawn-vehicles-and-machinery dispatch
and logistics operating workflows. Treat vulnerabilities as potentially high
impact even when the demo data is synthetic — this domain has both a
road-safety and an animal-welfare dimension.

## Do Not Disclose Publicly

Report privately before opening public issues for:

- credential exposure
- real driver, animal, vehicle, trip or operator data exposure
- authorization bypass
- CartageGovernor bypass
- any path that lets a proposal finalize a route/traffic-navigation
  decision, decide an animal-welfare/treatment disposition, or override a
  driver's on-road or animal-handling safety judgment
- audit-ledger tampering
- over-disclosure in reports or exports
- unsafe robot action dispatch

## Reporting

Use GitHub private vulnerability reporting when available for the repository.
If that is unavailable, contact the repository maintainers through the
cloud-itonami organization before publishing details.

Include:

- affected commit or version
- reproduction steps
- expected and actual behavior
- impact on driver/animal/vehicle/trip data, policy enforcement or audit
  logging
- suggested fix, if known

## Production Guidance

- Store secrets outside Git.
- Keep real driver/animal/vehicle/trip/operator data outside this
  repository.
- Run policy tests before deployment.
- Export and review audit logs regularly.
- Use least privilege for operators and service accounts.
