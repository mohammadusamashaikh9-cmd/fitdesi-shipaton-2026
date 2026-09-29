# Contributing to FitDesi AI

Thanks for helping improve FitDesi AI. This project is an early Android preview, so focused, well-evidenced changes are especially valuable.

## Start with an issue

Open or discuss an issue before beginning a non-trivial change. Describe the user problem, affected area, expected behavior, and any privacy, safety, or data-provenance implications. Do not use public issues for security vulnerabilities; follow [SECURITY.md](SECURITY.md).

## Development workflow

1. Create a focused feature branch from the current development branch.
2. Keep each pull request scoped to one coherent change.
3. Preserve offline behavior, existing persistence, safe defaults, and user data unless the change explicitly requires a reviewed migration.
4. Add or update focused tests when behavior changes.
5. Run the relevant checks and explain any environment blockers in the pull request.

## Repository safety

- Never commit API keys, tokens, passwords, keystores, certificates, `.env` files, `local.properties`, Firebase configuration, or private user data.
- Never commit APKs, AABs, Gradle build output, IDE state, or generated temporary artifacts.
- Do not enable remote providers, Firestore, destructive migrations, or other gated integrations without an explicit reviewed task.
- Keep third-party data, imagery, and licence claims accurate. Do not add unlicensed exercise media or food data.

## Health and nutrition claims

FitDesi AI provides general fitness and nutrition information. Contributions must not add medical diagnosis, treatment instructions, emergency advice, guaranteed outcomes, fabricated nutrition precision, or unsupported health claims. Preserve the existing escalation and safety boundaries.

## Respectful collaboration

Be constructive, specific, and respectful. Review the code and product behavior rather than the contributor. Explain trade-offs, welcome questions, and keep user privacy in mind when sharing examples or screenshots.
