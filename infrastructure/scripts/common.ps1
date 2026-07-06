#!/usr/bin/env pwsh
#requires -Version 7.4

<#
.SYNOPSIS
    Shared helpers dot-sourced by the infrastructure/scripts/*.ps1 deploy scripts.

.DESCRIPTION
    Host-logging shims plus the Azure PowerShell context bootstrap common to
    deploy.ps1, configure-dataverse.ps1, and configure-repo.ps1. Dot-source it:

        . (Join-Path $PSScriptRoot 'common.ps1')

    This file deliberately does NOT declare the Az modules in #requires - only the
    scripts that actually call Initialize-AzContext (deploy.ps1,
    configure-dataverse.ps1) declare those. configure-repo.ps1 sources this file
    purely for the logging helpers and never touches Az.
#>


function Write-Log  { param([string]$Message) Write-Host "==> $Message" -ForegroundColor Blue }
function Write-Warn { param([string]$Message) Write-Host "[!] $Message" -ForegroundColor Yellow }
function Write-DryRun { param([string]$Message) Write-Host "DRY-RUN: $Message" -ForegroundColor DarkGray }
function Stop-WithError { param([string]$Message) Write-Host "[x] $Message" -ForegroundColor Red; exit 1 }

<#
.SYNOPSIS
    Ensure an Azure PowerShell context on the target subscription.

.DESCRIPTION
    Bridges an Az context from the already-authenticated az CLI session (auth
    option B): reads the az login's tenant to drive Connect-AzAccount only when no
    Az context exists yet; an existing context on the wrong subscription is simply
    switched with Set-AzContext. Callers that use this (deploy.ps1,
    configure-dataverse.ps1) declare the Az module #requires themselves.
#>
function Initialize-AzContext {
    param([string]$SubscriptionId)

    $ctx = Get-AzContext
    if (${ctx}?.Subscription?.Id -eq $SubscriptionId) { return }

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
