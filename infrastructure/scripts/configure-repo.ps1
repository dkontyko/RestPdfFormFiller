#!/usr/bin/env pwsh
#requires -Version 7.0

<#
.SYNOPSIS
    Idempotently configure the GitHub repo to match the IaC.

.DESCRIPTION
    Manages:
      - GitHub environment `azure-dev`          (Function App deploy) + secrets
      - GitHub environment `power-platform-dev` (connector deploy)    + vars
      - a repository RULESET on `main` (require PR + status checks); NOT the
        legacy branch-protection API.

    CODEOWNERS is a committed file (.github/CODEOWNERS) and is not managed here.

    Client/tenant/subscription IDs are read from the Bicep deployment outputs by
    default, or fall back to a live lookup. These are identifiers, not secrets -
    no keys or passwords are handled by this script.

.PARAMETER Apply
    Perform the configuration. Without it, only a preview is shown.

.PARAMETER Yes
    Skip the interactive confirmation prompt when applying.

.PARAMETER PpConnectorId
    Optional Power Platform connector id; when set, stored as the
    power-platform-dev environment variable PP_CONNECTOR_ID. Defaults to the
    PP_CONNECTOR_ID environment variable.

.EXAMPLE
    ./configure-repo.ps1
    Preview the environment/ruleset changes without applying.

.EXAMPLE
    ./configure-repo.ps1 -Apply
    Apply after confirming at the prompt.

.EXAMPLE
    ./configure-repo.ps1 -Apply -Yes
    Apply without the confirmation prompt.

.NOTES
    Prereqs: gh CLI (authenticated), az CLI (logged in).
#>
[CmdletBinding()]
param(
    [switch]$Apply,
    [switch]$Yes,
    [string]$PpConnectorId = $env:PP_CONNECTOR_ID
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Preview by default; -Apply performs the changes.
$DryRun = -not $Apply

$Repo               = 'dkontyko/RestPdfFormFiller'
$ResourceGroup      = 'PdfFormFiller'
$AzureEnv           = 'azure-dev'
$PpEnv              = 'power-platform-dev'
$PpEnvironmentUrl   = 'https://orgddd15023.crm.dynamics.com'

function Write-Log  { param([string]$Message) Write-Host "==> $Message" -ForegroundColor Blue }
function Write-Warn { param([string]$Message) Write-Host "[!] $Message" -ForegroundColor Yellow }
function Write-DryRun { param([string]$Message) Write-Host "DRY-RUN: $Message" -ForegroundColor DarkGray }
function Stop-WithError { param([string]$Message) Write-Host "[x] $Message" -ForegroundColor Red; exit 1 }

foreach ($tool in 'gh', 'az') {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) { Stop-WithError "'$tool' is required." }
}
gh auth status 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) { Stop-WithError "gh is not authenticated. Run 'gh auth login'." }

if ($Apply -and -not $Yes) {
    $reply = Read-Host "Apply repo configuration (environments + ruleset) to '$Repo'? [y/N]"
    if ($reply -notmatch '^[Yy]$') { Stop-WithError 'Cancelled.' }
}

# --- Resolve identity values from the latest Bicep deployment outputs. --------
Write-Log "Reading deployment outputs from RG '$ResourceGroup'..."
$latestDeploy = az deployment group list -g $ResourceGroup `
    --query "sort_by([?starts_with(name, 'restpdf-infra-')], &properties.timestamp)[-1].name" -o tsv 2>$null
if ($latestDeploy) {
    $outputs = az deployment group show -g $ResourceGroup --name $latestDeploy `
        --query properties.outputs -o json | ConvertFrom-Json
    $FunctionClientId = $outputs.functionDeployClientId.value
    $TenantId         = $outputs.tenantId.value
    $SubscriptionId   = $outputs.subscriptionId.value
}
else {
    Write-Warn "No 'restpdf-infra-*' deployment found; falling back to live identity lookup."
    $FunctionClientId = az identity show -g $ResourceGroup -n gh-restpdfformfiller-deploy --query clientId -o tsv
    $TenantId         = az account show --query tenantId -o tsv
    $SubscriptionId   = az account show --query id -o tsv
}
if (-not ($FunctionClientId -and $TenantId -and $SubscriptionId)) {
    Stop-WithError 'Could not resolve client/tenant/subscription IDs.'
}

# --- Helpers ------------------------------------------------------------------

<#
.SYNOPSIS
    Ensure a GitHub environment exists and limit its deployments to 'main'.

.DESCRIPTION
    Creates/updates the environment with a custom branch policy, then reconciles
    its deployment branch policies down to exactly one 'main' policy (removing
    any strays). In -DryRun mode only the intended calls are printed.
#>
function Set-GhEnvironment {
    param([string]$EnvName)
    Write-Log "Ensuring environment '$EnvName' (deployments limited to 'main')..."
    if ($DryRun) { Write-DryRun "PUT repos/$Repo/environments/$EnvName + reconcile branch policies to 'main' only"; return }
    $body = @{
        deployment_branch_policy = @{ protected_branches = $false; custom_branch_policies = $true }
    } | ConvertTo-Json
    $body | gh api -X PUT "repos/$Repo/environments/$EnvName" --input - | Out-Null

    # Reconcile to exactly one 'main' policy: remove any other pre-existing
    # policies so the environment genuinely limits deployments to 'main'.
    # A failed list is treated as a hard error: silently assuming "no policies"
    # could skip pruning real stray policies and still report success, which is
    # exactly the misleading-audit state this reconciliation is meant to prevent.
    $raw = gh api "repos/$Repo/environments/$EnvName/deployment-branch-policies" 2>$null
    if ($LASTEXITCODE -ne 0 -or -not $raw) {
        Stop-WithError "Could not list deployment branch policies for '$EnvName' (API error or insufficient permissions). Aborting so the environment is not left in an unverified state."
    }
    try {
        $existing = $raw | ConvertFrom-Json
    } catch {
        Stop-WithError "Could not parse deployment branch policies for '$EnvName': $_"
    }
    $policies = @()
    if ($existing.PSObject.Properties.Name -contains 'branch_policies' -and $existing.branch_policies) {
        $policies = @($existing.branch_policies)
    }
    $hasMain = $false
    foreach ($policy in $policies) {
        if ($policy.name -eq 'main' -and $policy.type -eq 'branch') {
            $hasMain = $true
        } else {
            Write-Log "  removing stray deployment branch policy '$($policy.name)'"
            gh api -X DELETE "repos/$Repo/environments/$EnvName/deployment-branch-policies/$($policy.id)" 2>$null | Out-Null
        }
    }
    if (-not $hasMain) {
        gh api -X POST "repos/$Repo/environments/$EnvName/deployment-branch-policies" `
            -f 'name=main' -f 'type=branch' 2>$null | Out-Null
    }
}

<#
.SYNOPSIS
    Set (or update) a secret on a GitHub environment.
#>
function Set-GhEnvSecret {
    param([string]$EnvName, [string]$Name, [string]$Value)
    Write-Log "  secret $Name -> env $EnvName"
    if ($DryRun) { Write-DryRun "gh secret set $Name --env $EnvName"; return }
    gh secret set $Name --env $EnvName --repo $Repo --body $Value | Out-Null
}

<#
.SYNOPSIS
    Set (or update) a variable on a GitHub environment.
#>
function Set-GhEnvVar {
    param([string]$EnvName, [string]$Name, [string]$Value)
    Write-Log "  variable $Name -> env $EnvName"
    if ($DryRun) { Write-DryRun "gh variable set $Name --env $EnvName"; return }
    gh variable set $Name --env $EnvName --repo $Repo --body $Value | Out-Null
}

# --- azure-dev environment (Function App deploy). -----------------------------
Set-GhEnvironment $AzureEnv
Set-GhEnvSecret $AzureEnv 'AZURE_CLIENT_ID'       $FunctionClientId
Set-GhEnvSecret $AzureEnv 'AZURE_TENANT_ID'       $TenantId
Set-GhEnvSecret $AzureEnv 'AZURE_SUBSCRIPTION_ID' $SubscriptionId

# --- power-platform-dev environment (connector deploy). -----------------------
$connectorClientId = az identity show -g $ResourceGroup -n gh-customconnector-deploy --query clientId -o tsv 2>$null
Set-GhEnvironment $PpEnv
if ($connectorClientId) { Set-GhEnvSecret $PpEnv 'AZURE_CLIENT_ID' $connectorClientId }
Set-GhEnvSecret $PpEnv 'AZURE_TENANT_ID'    $TenantId
Set-GhEnvVar    $PpEnv 'PP_ENVIRONMENT_URL' $PpEnvironmentUrl
if ($PpConnectorId) { Set-GhEnvVar $PpEnv 'PP_CONNECTOR_ID' $PpConnectorId }

# --- Default-branch protection via a repository RULESET (modern API). ---------
# Status-check contexts are GitHub check-run/commit-status names
# (integration_id 15368 = the GitHub Actions app):
#   build                    -> maven.yml  (job id `build`)
#   copilot-review-complete  -> the copilot-review-gate.yml workflow. That job
#     waits a short grace period for Copilot to start reviewing the head commit;
#     if a review is in progress it blocks until Copilot finishes (regardless of
#     whether comments are left), otherwise it passes immediately. It is used
#     INSTEAD of requiring Copilot's own `copilot-pull-request-reviewer` check,
#     which is not reliably re-posted on pushes and stalls auto-merge. This makes
#     auto-merge wait for Copilot's (first-pass) review without deadlocking.
# CodeQL is enforced via the `code_scanning` rule ("Require code scanning
# results"), not as a status check. The `copilot_code_review` rule requests the
# Copilot review that the gate then waits on.
$RulesetName = 'main'
Write-Log "Configuring branch ruleset '$RulesetName' on the default branch..."
$ruleset = @{
    name        = $RulesetName
    target      = 'branch'
    enforcement = 'active'
    conditions  = @{ ref_name = @{ include = @('~DEFAULT_BRANCH'); exclude = @() } }
    rules       = @(
        @{ type = 'non_fast_forward' }
        @{ type = 'deletion' }
        @{ type = 'required_linear_history' }
        @{ type = 'pull_request'; parameters = @{
                required_approving_review_count   = 0
                dismiss_stale_reviews_on_push     = $false
                require_code_owner_review         = $false
                require_last_push_approval        = $false
                required_review_thread_resolution = $true
                required_reviewers                = @()
                allowed_merge_methods             = @('squash')
            }
        }
        @{ type = 'required_status_checks'; parameters = @{
                do_not_enforce_on_create             = $false
                strict_required_status_checks_policy = $false
                required_status_checks               = @(
                    @{ context = 'build'; integration_id = 15368 }
                    @{ context = 'copilot-review-complete'; integration_id = 15368 }
                )
            }
        }
        @{ type = 'code_scanning'; parameters = @{
                code_scanning_tools = @(
                    @{ tool = 'CodeQL'; security_alerts_threshold = 'high_or_higher'; alerts_threshold = 'errors' }
                )
            }
        }
        @{ type = 'copilot_code_review'; parameters = @{
                review_draft_pull_requests = $false
                review_on_push             = $true
            }
        }
    )
}
$rulesetJson = $ruleset | ConvertTo-Json -Depth 10

$existingRulesets = gh api "repos/$Repo/rulesets" 2>$null | ConvertFrom-Json
$existingId = ($existingRulesets | Where-Object { $_.name -eq $RulesetName } | Select-Object -First 1).id

if ($DryRun) {
    if ($existingId) { Write-DryRun "PUT repos/$Repo/rulesets/$existingId" } else { Write-DryRun "POST repos/$Repo/rulesets" }
    Write-Host $rulesetJson
}
elseif ($existingId) {
    $rulesetJson | gh api -X PUT "repos/$Repo/rulesets/$existingId" --input - | Out-Null
    if ($LASTEXITCODE -eq 0) { Write-Log "Ruleset updated (id $existingId)." } else { Write-Warn 'Ruleset update failed (requires admin).' }
}
else {
    $rulesetJson | gh api -X POST "repos/$Repo/rulesets" --input - | Out-Null
    if ($LASTEXITCODE -eq 0) { Write-Log 'Ruleset created.' } else { Write-Warn 'Ruleset creation failed (requires admin).' }
}

Write-Log "Done. Environments: https://github.com/$Repo/settings/environments"
Write-Log "Ruleset:      https://github.com/$Repo/settings/rules"
