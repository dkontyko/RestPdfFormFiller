#!/usr/bin/env pwsh
#requires -Version 7.0
#requires -Modules Az.Accounts, Az.Resources, Az.OperationalInsights, Az.ManagedServiceIdentity

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
    Prereqs:
      - az CLI, logged in (az login) with rights on RG PdfFormFiller. Still used
        for the what-if preview and the template validate (the Az equivalents
        expose a weaker diff / no validated-resource list, respectively).
      - Azure PowerShell modules: Az.Accounts, Az.Resources,
        Az.OperationalInsights, Az.ManagedServiceIdentity. The script bridges an
        Az context from the az login session, prompting Connect-AzAccount only
        if no Az context exists yet.
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

function Initialize-AzContext {
    # Bridge an Azure PowerShell context from the already-authenticated az CLI
    # session (auth option B). Connect-AzAccount is only invoked interactively
    # when no Az context exists yet; an existing context on the wrong
    # subscription is simply switched with Set-AzContext.
    param([string]$SubscriptionId)
    $ctx = Get-AzContext
    if ($ctx -and $ctx.Subscription -and $ctx.Subscription.Id -eq $SubscriptionId) { return }
    if (-not $ctx) {
        $tenantId = az account show --query tenantId -o tsv
        if ($LASTEXITCODE -ne 0 -or -not $tenantId) {
            Stop-WithError 'Could not read the az CLI tenant to bridge an Az context.'
        }
        Write-Log 'No Azure PowerShell context found; connecting (bridged from the az CLI session)...'
        Connect-AzAccount -Tenant $tenantId -Subscription $SubscriptionId | Out-Null
    }
    else {
        Set-AzContext -Subscription $SubscriptionId | Out-Null
    }
}

function Get-DesiredFederatedCredentialFromTemplate {
    # Resolve the intended FICs with `az deployment group validate` - a
    # lightweight template expansion (no what-if diff) that evaluates all
    # parameter / format() expressions server-side and lists every resource the
    # deployment would touch. Kept on the az CLI deliberately: the Az equivalent
    # (Test-AzResourceGroupDeployment) surfaces only validation errors, not the
    # validatedResources list this reconciliation depends on. Returns a hashtable
    # of UAMI name -> array of federated-credential names, kept in lock-step with
    # identities.bicep without duplicating the list here.
    #
    # The map is keyed by EVERY managed identity the template declares (parsed
    # from the identity resource IDs), so an identity with zero template FICs
    # still appears with an empty array and its stale FICs get pruned. Returns
    # $null when resolution itself fails (validate error, or no identities
    # parsed) so the caller can skip pruning rather than mistake a false-empty
    # for "the template wants no FICs" and delete live credentials.
    $ids = az deployment group validate `
        --resource-group $ResourceGroup `
        --template-file $Template `
        --parameters $ParamFile `
        --query 'properties.validatedResources[].id' -o tsv 2>$null
    if ($LASTEXITCODE -ne 0) {
        Write-Warn 'Could not validate the template to resolve federated credentials (API error or insufficient permissions).'
        return $null
    }
    $map = @{}
    foreach ($id in @($ids | Where-Object { $_ })) {
        # A federated-credential child: record it under its parent identity.
        if ($id -match '/userAssignedIdentities/(?<uami>[^/]+)/federatedIdentityCredentials/(?<fic>[^/]+)$') {
            $uami = $Matches['uami']
            if (-not $map.ContainsKey($uami)) { $map[$uami] = @() }
            $map[$uami] += $Matches['fic']
        }
        # The managed identity resource itself: establishes the authoritative set
        # of identities to reconcile, even those declaring zero FICs.
        elseif ($id -match '/userAssignedIdentities/(?<uami>[^/]+)$') {
            if (-not $map.ContainsKey($Matches['uami'])) { $map[$Matches['uami']] = @() }
        }
    }
    if ($map.Count -eq 0) {
        # The template always declares the deploy identities; zero here means the
        # resolution didn't work, not a genuinely empty template. Skip pruning.
        Write-Warn 'Resolved no managed identities from the template; skipping FIC reconciliation to avoid deleting live credentials on a false-empty.'
        return $null
    }
    return $map
}

<#
.SYNOPSIS
    Reconcile a single managed identity's federated credentials against the
    template, pruning (or, in preview, reporting) any it does not declare.

.DESCRIPTION
    $WantedFic is authoritative: an empty array means the template declares
    this identity but wants NO federated credentials, so every live FIC is a
    prune target. A failed list (e.g. a permissions error) is deliberately
    never treated as "no FICs" - that would silently leave stale credentials
    in place - so it returns $false instead of an empty set.

.OUTPUTS
    [bool] $true when the identity reconciled cleanly (including when there
    is nothing to do); $false when the list, or any individual delete, failed.
#>
function Sync-FederatedCredentialForIdentity {
    param(
        [string]$IdentityName,
        [string[]]$WantedFic,
        [bool]$Prune
    )

    try {
        $live = Get-AzFederatedIdentityCredential -ResourceGroupName $ResourceGroup `
            -IdentityName $IdentityName -ErrorAction Stop
    }
    catch {
        Write-Warn "Could not list federated credentials for '$IdentityName' ($($_.Exception.Message)); skipping its reconciliation."
        return $false
    }

    $live = @($live.Name | Where-Object { $_ })
    if (-not $live) { return $true }

    $stale = @($live | Where-Object { $WantedFic -notcontains $_ })
    if (-not $stale) {
        Write-Log "FICs on '$IdentityName' already match the template."
        return $true
    }

    if (-not $Prune) {
        $stale | ForEach-Object { Write-Warn "Would prune stale FIC '$_' from '$IdentityName' (not in the template)." }
        return $true
    }

    $ok = $true
    foreach ($fic in $stale) {
        Write-Log "Pruning stale FIC '$fic' from '$IdentityName'..."
        try {
            Remove-AzFederatedIdentityCredential -ResourceGroupName $ResourceGroup `
                -IdentityName $IdentityName -Name $fic -Confirm:$false -ErrorAction Stop | Out-Null
        }
        catch {
            Write-Warn "Failed to delete FIC '$fic' from '$IdentityName' ($($_.Exception.Message))."
            $ok = $false
        }
    }
    return $ok
}

<#
.SYNOPSIS
    Reconcile every managed identity's federated credentials against the
    template, delegating each to Sync-FederatedCredentialForIdentity.

.DESCRIPTION
    A $null $Desired means upstream resolution failed or was skipped (already
    warned), which is treated as a clean no-op.

.OUTPUTS
    [bool] $true only if every identity reconciled cleanly; $false if any
    list or delete failed, so the caller can decide whether to fail the run.
#>
function Sync-FederatedCredential {
    param([hashtable]$Desired, [bool]$Prune)

    if (-not $Desired) { return $true }

    $outcomes = $Desired.Keys | ForEach-Object {
        Sync-FederatedCredentialForIdentity -IdentityName $_ -WantedFic $Desired[$_] -Prune $Prune
    }
    return $outcomes -notcontains $false
}

if (-not (Get-Command az -ErrorAction SilentlyContinue)) {
    Stop-WithError 'az CLI is required (used for the what-if preview and template validate).'
}

az account show 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) { Stop-WithError "Not logged in. Run 'az login' first." }
az account set --subscription $SubscriptionId | Out-Null

# Bridge an Azure PowerShell context from that az session (auth option B) for the
# Az cmdlets below: workspace preflight, deployment, and FIC reconciliation.
Initialize-AzContext -SubscriptionId $SubscriptionId

# --- Preflight: a Log Analytics workspace region cannot be changed in place.
# If the old westus2 workspace still exists, Bicep will collide on the name.
# It must be deleted first (data history is intentionally discarded - see README).
# A not-found workspace throws under the Stop preference; treat that as "absent".
$existingWs = try {
    Get-AzOperationalInsightsWorkspace -ResourceGroupName $ResourceGroup -Name $WorkspaceName -ErrorAction Stop
}
catch { $null }
if ($existingWs -and $existingWs.Location -ne $Location) {
    Write-Warn "Workspace '$WorkspaceName' currently exists in '$($existingWs.Location)', but the"
    Write-Warn "target is '$Location'. A workspace region cannot be changed in place."
    Write-Warn 'Delete the old workspace first (this discards its log history):'
    Write-Warn "  Remove-AzOperationalInsightsWorkspace -ResourceGroupName $ResourceGroup -Name $WorkspaceName -ForceDelete -Force"
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
    # Preview only: a list failure is surfaced as a warning (above) but does not
    # fail the read-only what-if.
    $null = Sync-FederatedCredential -Desired $desiredFics -Prune:$false
    Write-Log 'what-if complete. Re-run with -Apply to deploy.'
    exit 0
}

if (-not $Yes) {
    $reply = Read-Host "Apply this deployment to RG '$ResourceGroup'? [y/N]"
    if ($reply -notmatch '^[Yy]$') { Stop-WithError 'Cancelled.' }
}

$deployName = "restpdf-infra-$(Get-Date -Format 'yyyyMMddHHmmss')"
Write-Log "Deploying ($deployName)..."
# A deployment failure throws under $ErrorActionPreference = 'Stop', so there is
# no $LASTEXITCODE check to make here - the run aborts on its own.
$deployment = New-AzResourceGroupDeployment `
    -ResourceGroupName $ResourceGroup `
    -TemplateFile $Template `
    -TemplateParameterFile $ParamFile `
    -Name $deployName

# Prune any FICs not declared in the template (the new ones now exist).
Write-Log 'Reconciling federated credentials to match the template...'
$desiredFics = Get-DesiredFederatedCredentialFromTemplate
$reconcileOk = Sync-FederatedCredential -Desired $desiredFics -Prune:$true

Write-Log 'Deployment complete. Outputs:'
$deployment.Outputs | ConvertTo-Json -Depth 20

# The deployment itself succeeded above; if the post-deploy FIC reconciliation
# could not complete, fail loudly (non-zero exit) so a stale credential is not
# left silently in place - but make clear the deployment did succeed.
if (-not $reconcileOk) {
    Stop-WithError 'Deployment succeeded, but federated-credential reconciliation was incomplete (see warnings above). Investigate identity permissions and re-run to prune stale credentials.'
}
