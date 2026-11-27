# Contributing

Thanks for helping improve zAccounting. The project is in an early migration stage. The target web application, accounting modules and production deployment are not complete.

## Before starting

- Check the [migration roadmap](docs/migration-roadmap.md) and [architecture overview](docs/architecture.md).
- For a substantial change, open or discuss an issue first so its product and accounting rules are clear.
- Treat the existing app in `reference/` as evidence of behavior, not as the authority for accounting correctness.
- Keep changes focused. Avoid introducing infrastructure or dependencies without a concrete need.

## Development expectations

- Follow the repository guidance in [AGENTS.md](AGENTS.md).
- Keep frontend presentation in `apps/web/` and business rules, authorization and persistence in `apps/api/`.
- Use `BigDecimal` and sized `NUMERIC` for money. Do not use floating-point values for financial calculations.
- Never edit posted accounting history in place. Corrections must use reversals.
- Enforce business membership and permissions on every request and related object lookup.
- Keep imports staged for review. Do not automatically post imported transactions.
- Do not add real financial data, credentials, tokens, or private environment files to commits.
- Document consequential accounting decisions and any changes to the migration plan.

## Validation

For API changes, start the local PostgreSQL service and follow the commands in [local development](docs/local-development.md). Run the relevant Maven checks and report which commands passed. Do not claim validation that was not performed.

Frontend validation instructions will be added when `apps/web/` is initialized.

## Pull requests

Pull requests should describe the behavior changed, why it changed, the validation performed and any remaining limitations. Include schema migrations, API documentation and focused regression coverage when the change requires them.

Before requesting review, confirm that:

- No secrets or real financial records are included.
- Accounting and authorization behavior is described and covered by appropriate checks.
- Documentation and roadmap status match the implementation.
- New dependencies and infrastructure are justified.
