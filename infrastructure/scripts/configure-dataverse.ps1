#!/usr/bin/env pwsh

<#
.SYNOPSIS
    Register the connector-deploy managed identity as a Dataverse Application
    User with a security role.

.DESCRIPTION
    Registers the UAMI as a Dataverse Application User and assigns it a security
    role, so `pac` (in the connector deploy workflow) can authenticate to
    Dataverse.

    SCOPE: Dataverse only. The GitHub OIDC federated credential on the UAMI is
    owned by the Bicep (infrastructure/bicep/modules/identities.bicep) - this
    script does NOT touch federated credentials (the original external script's
    "wipe all FICs" behaviour was intentionally dropped).

    Safe to re-run: every step checks for existing state before creating.

.PARAMETER Apply
    Perform the registration. Without it, only a preview is shown.

.PARAMETER Yes
    Skip the interactive confirmation prompt when applying.

.PARAMETER SubscriptionId
    Azure subscription containing the managed identity.

.PARAMETER ResourceGroup
    Resource group containing the managed identity.

.PARAMETER UamiName
    Name of the user-assigned managed identity to register.

.PARAMETER DataverseUrl
    Target Dataverse environment URL (no trailing slash required).

.PARAMETER SecurityRole
    Dataverse security role to assign to the application user.

.EXAMPLE
    ./configure-dataverse.ps1
    Preview the Dataverse changes without applying.

.EXAMPLE
    ./configure-dataverse.ps1 -Apply
    Apply after confirming at the prompt.

.EXAMPLE
    ./configure-dataverse.ps1 -Apply -Yes
    Apply without the confirmation prompt.

.NOTES
    Prereqs: az CLI, logged in as a Dataverse System Administrator on the
    target environment.
#>
[CmdletBinding()]
param(
    [switch]$Apply,
    [switch]$Yes,
    [string]$SubscriptionId = '2f783474-e127-4225-aeab-43f265e00aaa',
    [string]$ResourceGroup  = 'PdfFormFiller',
    [string]$UamiName       = 'gh-customconnector-deploy',
    [string]$DataverseUrl   = 'https://orgddd15023.crm.dynamics.com',
    [string]$SecurityRole   = 'System Customizer'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Preview by default; -Apply performs the changes.
$DryRun = -not $Apply

function Write-Log  { param([string]$Message) Write-Host "==> $Message" -ForegroundColor Blue }
function Write-Warn { param([string]$Message) Write-Host "[!] $Message" -ForegroundColor Yellow }
function Write-DryRun { param([string]$Message) Write-Host "DRY-RUN: $Message" -ForegroundColor DarkGray }
function Stop-WithError { param([string]$Message) Write-Host "[x] $Message" -ForegroundColor Red; exit 1 }

if (-not (Get-Command az -ErrorAction SilentlyContinue)) { Stop-WithError 'az CLI is required.' }
$DataverseUrl = $DataverseUrl.TrimEnd('/')
az account set --subscription $SubscriptionId | Out-Null

if ($Apply -and -not $Yes) {
    $reply = Read-Host "Register the connector identity in Dataverse '$DataverseUrl'? [y/N]"
    if ($reply -notmatch '^[Yy]$') { Stop-WithError 'Cancelled.' }
}

Write-Log "Resolving UAMI '$UamiName' clientId..."
$uamiClientId = az identity show -g $ResourceGroup -n $UamiName --query clientId -o tsv
if (-not $uamiClientId) { Stop-WithError "Could not read UAMI '$UamiName' in RG '$ResourceGroup'." }
Write-Log "UAMI clientId = $uamiClientId"

$api = "$DataverseUrl/api/data/v9.2"
Write-Log "Acquiring a Dataverse admin token for $DataverseUrl..."
$token = az account get-access-token --resource $DataverseUrl --query accessToken -o tsv
if (-not $token) { Stop-WithError 'Could not get a Dataverse token. Are you a System Administrator on this env?' }

$headers = @{
    Authorization      = "Bearer $token"
    'OData-MaxVersion' = '4.0'
    'OData-Version'    = '4.0'
    Accept             = 'application/json'
}

function Invoke-Dv {
    param([string]$Path, [string]$Method = 'Get', $Body = $null, [hashtable]$ExtraHeaders = @{})
    $h = $headers.Clone()
    foreach ($k in $ExtraHeaders.Keys) { $h[$k] = $ExtraHeaders[$k] }
    $params = @{ Uri = "$api/$Path"; Method = $Method; Headers = $h }
    if ($Body) { $params.Body = $Body; $params.ContentType = 'application/json' }
    Invoke-RestMethod @params
}

Write-Log 'Looking up root business unit...'
$bu = Invoke-Dv 'businessunits?$select=businessunitid&$filter=parentbusinessunitid%20eq%20null'
$buId = $bu.value[0].businessunitid
if (-not $buId) { Stop-WithError 'Could not find the root business unit.' }

$roleNameEncoded = $SecurityRole -replace ' ', '%20'
$roleFilter = "name%20eq%20'$roleNameEncoded'%20and%20_businessunitid_value%20eq%20$buId"
Write-Log "Looking up security role '$SecurityRole'..."
$role = Invoke-Dv "roles?`$select=roleid&`$filter=$roleFilter"
$roleId = $role.value[0].roleid
if (-not $roleId) { Stop-WithError "Security role '$SecurityRole' not found in the root business unit." }

Write-Log "Checking for an existing application user for clientId $uamiClientId..."
$existingUser = Invoke-Dv "systemusers?`$select=systemuserid&`$filter=applicationid%20eq%20$uamiClientId"
$systemUserId = $existingUser.value[0].systemuserid

if ($systemUserId) {
    Write-Warn "Application user already exists (systemuserid=$systemUserId)."
}
elseif ($DryRun) {
    Write-DryRun "Create Dataverse application user for clientId $uamiClientId"
}
else {
    Write-Log 'Creating application user...'
    $createBody = @{
        applicationid              = $uamiClientId
        'businessunitid@odata.bind' = "/businessunits($buId)"
    } | ConvertTo-Json
    $resp = Invoke-Dv 'systemusers' -Method Post -Body $createBody -ExtraHeaders @{ Prefer = 'return=representation' }
    $systemUserId = $resp.systemuserid
    if (-not $systemUserId) { Stop-WithError 'Application user creation did not return a systemuserid.' }
    Write-Log "Application user created (systemuserid=$systemUserId)."
}

if ($systemUserId) {
    $hasRole = (Invoke-Dv "systemusers($systemUserId)/systemuserroles_association?`$select=roleid&`$filter=roleid%20eq%20$roleId").value
    if ($hasRole) {
        Write-Warn "Role '$SecurityRole' already assigned."
    }
    elseif ($DryRun) {
        Write-DryRun "Assign role '$SecurityRole' to app user $systemUserId"
    }
    else {
        Write-Log "Assigning role '$SecurityRole'..."
        $refBody = @{ '@odata.id' = "$api/roles($roleId)" } | ConvertTo-Json
        Invoke-Dv "systemusers($systemUserId)/systemuserroles_association/`$ref" -Method Post -Body $refBody | Out-Null
        Write-Log 'Role assigned.'
    }
}

Write-Log 'Done.'
