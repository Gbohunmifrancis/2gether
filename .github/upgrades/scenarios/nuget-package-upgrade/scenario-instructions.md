# NuGet Package Upgrade

## Strategy
Upgrade the direct MailKit dependency to remove the vulnerable transitive System.Security.Cryptography.Pkcs version.

## Preferences
- **Flow Mode**: Automatic
- **Commit Strategy**: After Each Task
- **Pace**: Standard
- **Scope**: Entire solution
- **Packages**: `MailKit` 4.18.2
- **Vulnerability Target**: `System.Security.Cryptography.Pkcs` 10.0.0 (high and moderate advisories)
- **Version Policy**: Latest supported stable version
- **Assessment Scan**: Quick scan (no full semantic breaking-change scan)

## Decisions
- Upgrade MailKit rather than adding a direct override for System.Security.Cryptography.Pkcs; the dependency graph shows MailKit → MimeKit → Pkcs in the affected restore graphs.
- Apply the version change in `src/Twogether.Infrastructure/Twogether.Infrastructure.csproj`; Central Package Management is not configured.

## Custom Instructions
<!-- Task-specific overrides: "For {taskId}: {instruction}" -->

## Source Control
- **Source Branch**: `main`
- **Working Branch**: `nuget-package-upgrade`
- **Pending Changes**: None
- **Commit Strategy**: After Each Task
- **Branch Sync**: Auto (Merge)
