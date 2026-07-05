#!/usr/bin/env pwsh
#requires -Version 7.0

<#
.SYNOPSIS
    Provision / reconcile the RestPdfFormFiller infrastructure.

.DESCRIPTION
    Deploys the Bicep under ../bicep against resource group PdfFormFiller.
    Defaults to a non-destructive what-if; pass -Apply to deploy for real.
    Includes a preflight guard for the westus2 -> centralus Log Analytics
    workspace move (a workspace region cannot be changed in place).

    Also reconciles federated identity credentials: any FIC on the deploy
    identities whose name is not declared in the template is reported during a
    what-if and pruned on -Apply (ARM/Bicep does not delete out-of-template
    child resources on its own).

.PARAMETER Apply
    Perform the deployment. Without it, only a what-if preview is shown.

.PARAMETER Yes
    Skip the interactive confirmation prompt when applying.

.EXAMPLE
    ./deploy.ps1
    Preview the changes (what-if) without deploying.

.EXAMPLE
    ./deploy.ps1 -Apply
    Deploy after confirming at the prompt.

.EXAMPLE
    ./deploy.ps1 -Apply -Yes
    Deploy without the confirmation prompt.

.NOTES
    Prereqs: az CLI, logged in (az login) with rights on RG PdfFormFiller.
#>
[CmdletBinding()]
param(
    [switch]$Apply,
    [switch]$Yes
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$SubscriptionId = '2f783474-e127-4225-aeab-43f265e00aaa'
$ResourceGroup  = 'PdfFormFiller'
$Location       = 'centralus'
$WorkspaceName  = 'PdfFormFillerLogs'
$BicepDir  = Join-Path (Join-Path $PSScriptRoot '..') 'bicep'
$Template  = Join-Path $BicepDir 'main.bicep'
$ParamFile = Join-Path $BicepDir 'main.dev.bicepparam'

function Write-Log  { param([string]$Message) Write-Host "==> $Message" -ForegroundColor Blue }
function Write-Warn { param([string]$Message) Write-Host "[!] $Message" -ForegroundColor Yellow }
function Stop-WithError { param([string]$Message) Write-Host "[x] $Message" -ForegroundColor Red; exit 1 }

function Get-DesiredFederatedCredentialFromTemplate {
    # Resolve the intended FICs with `az deployment group validate` - a
    # lightweight template expansion (no what-if diff) that evaluates all
    # parameter / format() expressions server-side and lists every resource the
    # deployment would touch. Returns a hashtable of UAMI name -> array of
    # federated-credential names, kept in lock-step with identities.bicep
    # without duplicating the list here.
    $ids = az deployment group validate `
        --resource-group $ResourceGroup `
        --template-file $Template `
        --parameters $ParamFile `
        --query 'properties.validatedResources[].id' -o tsv 2>$null
    $map = @{}
    foreach ($id in @($ids | Where-Object { $_ })) {
        if ($id -match '/userAssignedIdentities/(?<uami>[^/]+)/federatedIdentityCredentials/(?<fic>[^/]+)$') {
            $uami = $Matches['uami']
            if (-not $map.ContainsKey($uami)) { $map[$uami] = @() }
            $map[$uami] += $Matches['fic']
        }
    }
    if ($map.Count -eq 0) {
        Write-Warn 'Could not resolve federated credentials from the template (validate returned none).'
    }
    return $map
}

function Sync-FederatedCredential {
    param([hashtable]$Desired, [bool]$Prune)
    if ($Desired.Count -eq 0) { Write-Warn 'No template FICs resolved; skipping reconciliation.'; return }
    foreach ($uami in $Desired.Keys) {
        $wanted = $Desired[$uami]
        $actual = @(az identity federated-credential list --identity-name $uami `
                -g $ResourceGroup --query '[].name' -o tsv 2>$null | Where-Object { $_ })
        if (-not $actual) { continue }
        $extras = @($actual | Where-Object { $wanted -notcontains $_ })
        if (-not $extras) { Write-Log "FICs on '$uami' already match the template."; continue }
        foreach ($fic in $extras) {
            if ($Prune) {
                Write-Log "Pruning stale FIC '$fic' from '$uami'..."
                az identity federated-credential delete --name $fic `
                    --identity-name $uami -g $ResourceGroup --yes | Out-Null
                if ($LASTEXITCODE -ne 0) { Write-Warn "Failed to delete FIC '$fic' from '$uami'." }
            }
            else {
                Write-Warn "Would prune stale FIC '$fic' from '$uami' (not in the template)."
            }
        }
    }
}

if (-not (Get-Command az -ErrorAction SilentlyContinue)) { Stop-WithError 'az CLI is required.' }

az account show 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) { Stop-WithError "Not logged in. Run 'az login' first." }
az account set --subscription $SubscriptionId | Out-Null

# --- Preflight: a Log Analytics workspace region cannot be changed in place.
# If the old westus2 workspace still exists, Bicep will collide on the name.
# It must be deleted first (data history is intentionally discarded - see README).
$existingWsLocation = az monitor log-analytics workspace show `
    -g $ResourceGroup -n $WorkspaceName --query location -o tsv 2>$null
if ($existingWsLocation -and $existingWsLocation -ne $Location) {
    Write-Warn "Workspace '$WorkspaceName' currently exists in '$existingWsLocation', but the"
    Write-Warn "target is '$Location'. A workspace region cannot be changed in place."
    Write-Warn 'Delete the old workspace first (this discards its log history):'
    Write-Warn "  az monitor log-analytics workspace delete -g $ResourceGroup -n $WorkspaceName --force true --yes"
    Stop-WithError 'Aborting so the region move is a deliberate, confirmed step.'
}

if (-not $Apply) {
    Write-Log 'Running what-if (no changes will be made)...'
    az deployment group what-if `
        --resource-group $ResourceGroup `
        --template-file $Template `
        --parameters $ParamFile
    if ($LASTEXITCODE -ne 0) { Stop-WithError 'what-if failed.' }
    Write-Log 'Checking federated-credential drift (pruned on -Apply)...'
    $desiredFics = Get-DesiredFederatedCredentialFromTemplate
    Sync-FederatedCredential -Desired $desiredFics -Prune:$false
    Write-Log 'what-if complete. Re-run with -Apply to deploy.'
    exit 0
}

if (-not $Yes) {
    $reply = Read-Host "Apply this deployment to RG '$ResourceGroup'? [y/N]"
    if ($reply -notmatch '^[Yy]$') { Stop-WithError 'Cancelled.' }
}

$deployName = "restpdf-infra-$(Get-Date -Format 'yyyyMMddHHmmss')"
Write-Log "Deploying ($deployName)..."
az deployment group create `
    --resource-group $ResourceGroup `
    --template-file $Template `
    --parameters $ParamFile `
    --name $deployName
if ($LASTEXITCODE -ne 0) { Stop-WithError 'Deployment failed.' }

# Prune any FICs not declared in the template (the new ones now exist).
Write-Log 'Reconciling federated credentials to match the template...'
$desiredFics = Get-DesiredFederatedCredentialFromTemplate
Sync-FederatedCredential -Desired $desiredFics -Prune:$true

Write-Log 'Deployment complete. Outputs:'
az deployment group show -g $ResourceGroup --name $deployName --query properties.outputs -o jsonc
