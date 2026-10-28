# NuGet Package Upgrade Plan

## Overview

**Target**: Remove the high- and moderate-severity advisories on transitive `System.Security.Cryptography.Pkcs` 10.0.0 by upgrading its direct dependency, MailKit, to 4.18.2.
**Scope**: Entire solution; MailKit is directly referenced by `Twogether.Infrastructure` and flows transitively into the affected API and unit-test projects.

## Tasks

### 01-upgrade-mailkit: Upgrade MailKit to 4.18.2

Update the MailKit reference in `src/Twogether.Infrastructure/Twogether.Infrastructure.csproj` from 4.18.1 to the assessed unified stable version 4.18.2. The quick API assessment found no source-breaking public API changes (`apidiff/MailKit.apidiff.md`). Verify that the resolved `MimeKit`/`System.Security.Cryptography.Pkcs` chain is no longer vulnerable, then build and test the solution.

**Done when**: MailKit resolves to 4.18.2, the solution-wide vulnerability audit reports no vulnerable packages, and the solution builds and tests pass.
