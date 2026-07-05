# Infrastructure (`infrastructure/`)

Infrastructure-as-Code for the **RestPdfFormFiller** workload. The Bicep here is
intended to be a **complete, faithful record** of Azure resource group
`PdfFormFiller`, and the scripts codify the GitHub + Power Platform (Dataverse)
configuration that surrounds it.

Nothing here is applied automatically — you deploy Bicep with a what-if first,
and run the scripts explicitly.

## Layout

```
infrastructure/
  bicep/
    main.bicep                 # RG-scoped orchestrator
    main.dev.bicepparam        # dev values (mirrors the live resources)
    modules/
      storage.bicep            # Function App storage account
      monitoring.bicep         # Log Analytics + App Insights + smart-detector alert
      functionApp.bicep        # Y1 (Windows) plan + Function App + dev slot
      identities.bicep         # both deploy UAMIs + GitHub OIDC federated creds
      roleAssignments.bicep    # least-privilege RBAC
  scripts/
    deploy.ps1                 # what-if / deploy wrapper (with region-move guard)
    configure-repo.ps1         # GitHub environments + main ruleset (idempotent)
    configure-dataverse.ps1    # Dataverse application user + role (idempotent)
```

## What it provisions (RG `PdfFormFiller`, region `centralus`)

| Resource | Notes |
|---|---|
| Storage `pdfformfilleraf71` | `Standard_LRS`; required by the Functions runtime |
| Log Analytics `PdfFormFillerLogs` | `centralus`, 31-day retention, 1 GB/day cap |
| App Insights `RestPdfFormFiller` | workspace-based, **Entra-only ingestion** |
| Smart-detector alert + action group | Failure Anomalies (captured for completeness) |
| Plan `ASP-PdfFormFiller-a3cf` | `Y1` Dynamic (Consumption), Windows |
| Function App `RestPdfFormFiller` + `dev` slot | Java 25, system-assigned identity on both |
| UAMI `gh-restpdfformfiller-deploy` | Function deploy; FIC `environment:azure-dev` |
| UAMI `gh-customconnector-deploy` | Connector deploy; FIC `environment:power-platform-dev` |
| Role assignments | deploy UAMI → Website Contributor; site/slot SAMI → storage data roles + Monitoring Metrics Publisher |

## Deploy order

```pwsh
cd infrastructure/scripts

# 1. Preview (no changes). Also guards against the westus2 -> centralus move.
./deploy.ps1

# 2. Apply once the what-if looks right.
./deploy.ps1 -Apply

# 3. Configure the GitHub repo (environments, secrets, main ruleset).
./configure-repo.ps1             # preview
./configure-repo.ps1 -Apply

# 4. Register the connector identity in Dataverse (only needed once / on change).
./configure-dataverse.ps1             # preview
./configure-dataverse.ps1 -Apply
```

Check for drift at any time with `./deploy.ps1` (what-if).

## Important one-time / manual notes

- **Workspace region move (westus2 → centralus).** A Log Analytics workspace
  region cannot be changed in place, and the name can't collide. `deploy.ps1`
  **blocks** if the old westus2 workspace still exists and tells you to delete it
  first (this discards its prior log history, which was accepted):
  ```bash
  az monitor log-analytics workspace delete -g PdfFormFiller -n PdfFormFillerLogs --force true --yes
  ```

- **Entra-only App Insights ingestion is staged.** `enforceEntraOnlyIngestion`
  turns off key-based ingestion (`DisableLocalAuth`). Deploy it as `false` first,
  confirm telemetry still flows via the managed identity (Java agent + Functions
  host both honor `APPLICATIONINSIGHTS_AUTHENTICATION_STRING=Authorization=AAD`),
  then set it to `true`.

- **Storage key rotation.** `AzureWebJobsStorage` is keyless (identity-based), so
  it no longer depends on the account key. The content share still uses a
  key-based connection built via `listKeys()` at deploy. To roll the (previously
  exposed) keys safely: regenerate `key1`, redeploy (`./deploy.ps1 -Apply`) so
  the content-share connection string refreshes, verify the app is healthy, then
  roll `key2`.

- **FIC subject change.** The function-deploy workflow now declares
  `environment: azure-dev`, so its GitHub OIDC subject is
  `repo:dkontyko/RestPdfFormFiller:environment:azure-dev`. The Bicep defines the
  matching federated credential. These must stay in sync or `azure/login` fails.

## App settings intentionally removed

`functionApp.bicep` declares app settings authoritatively, which drops the
following stale/debug/legacy settings found on the live app (mainly the `dev`
slot). Review before applying:

- `APPINSIGHTS_INSTRUMENTATIONKEY` — legacy; replaced by the connection string.
- `MICROSOFT_PROVIDER_AUTHENTICATION_SECRET` — Easy Auth is disabled.
- `clientId`, `clientSecret`, `tenantId`, `WebUrl` — stale OBO/Graph leftovers.
- `JAVA_OPTS`, `HTTP_PLATFORM_DEBUG_PORT` — remote-debug artifacts.
- `WEBSITE_HTTPLOGGING_RETENTION_DAYS` — slot-only drift.
- `WEBSITE_RUN_FROM_PACKAGE` — deployment-managed; the deploy workflow re-adds it.
