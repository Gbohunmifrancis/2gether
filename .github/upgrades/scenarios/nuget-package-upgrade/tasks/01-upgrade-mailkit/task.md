# 01-upgrade-mailkit: Upgrade MailKit to 4.18.2

Update the MailKit reference in `src/Twogether.Infrastructure/Twogether.Infrastructure.csproj` from 4.18.1 to the assessed unified stable version 4.18.2. The quick API assessment found no source-breaking public API changes (`apidiff/MailKit.apidiff.md`). Verify that the resolved `MimeKit`/`System.Security.Cryptography.Pkcs` chain is no longer vulnerable, then build and test the solution.

**Done when**: MailKit resolves to 4.18.2, the solution-wide vulnerability audit reports no vulnerable packages, and the solution builds and tests pass.

## Research Findings

- The vulnerable `System.Security.Cryptography.Pkcs` 10.0.0 dependency resolves through `Twogether.Infrastructure` → MailKit 4.18.1 → MimeKit 4.18.1; the API and unit-test projects inherit it through `Twogether.Infrastructure`.
- `MailKit` is directly defined in `src/Twogether.Infrastructure/Twogether.Infrastructure.csproj`; the repository does not use Central Package Management.
- Assessment recommends unified stable MailKit 4.18.2. The API diff reports no source-breaking public API changes. The implementation is limited to the direct package version; solution-wide vulnerability audit, build, and tests are the required validation.
