#requires -Version 7.0

<#
.SYNOPSIS
    Auto-merge-safe gate that waits for GitHub Copilot's review of a PR's head
    commit to finish before the required check succeeds.

.DESCRIPTION
    Copilot code review's own `copilot-pull-request-reviewer` check is not
    reliably re-posted on every push, so requiring it directly stalls
    auto-merge. This script backs the `copilot-review-complete` required check
    instead. It waits a short grace period, then, while Copilot is reviewing the
    current head commit (its check run is queued/in_progress) OR has an
    outstanding review request that it has not yet answered for this head, it
    blocks until the review finishes. If Copilot is not reviewing this commit
    (for example a follow-up push it does not re-review), it returns immediately.

    The script never throws for a "not reviewed" state - it only delays - so the
    gate never fails, it only holds the PR until Copilot is done. Any feedback
    Copilot leaves is enforced separately by the ruleset's
    require-conversation-resolution rule.

    Signal reliability and impersonation-hardening (verified against this repo):
    the requested_reviewers REST endpoint does NOT list Copilot, so it is not
    used; the issue timeline `review_requested` events and the check run are
    reliable. To stop someone impersonating Copilot with a look-alike username
    (e.g. 'copilotx' or 'joe_copilot_smith'), reviews and review requests are
    matched on the bot's IMMUTABLE user id and type 'Bot' - never the login
    (which differs anyway: 'copilot-pull-request-reviewer[bot]' in the reviews
    API vs 'Copilot' in the timeline, but the same id 175728472 in both).

    Requires the `gh` CLI authenticated via the GH_TOKEN environment variable.

.PARAMETER Repo
    owner/name of the repository (e.g. dkontyko/RestPdfFormFiller).

.PARAMETER Pr
    Pull request number.

.PARAMETER HeadSha
    The PR head commit SHA to gate on.

.PARAMETER InitialWait
    Seconds to wait for Copilot to start before re-evaluating (default 30).

.PARAMETER PollInterval
    Seconds between polls while waiting (default 15).

.PARAMETER MaxWait
    Hard cap in seconds before giving up and passing (default 1200).

.PARAMETER CopilotBotId
    Immutable GitHub user id of the genuine Copilot reviewer bot (default
    175728472). Reviews and review requests are matched on this id plus type
    'Bot' - not the login - so a look-alike username cannot spoof Copilot.

.PARAMETER ActionsAppId
    GitHub App id that posts Copilot's review check run (default 15368,
    github-actions).

.EXAMPLE
    ./copilot-review-gate.ps1 -Repo dkontyko/RestPdfFormFiller -Pr 152 -HeadSha 8d961ec...
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Repo,
    [Parameter(Mandatory)][string]$Pr,
    [Parameter(Mandatory)][string]$HeadSha,
    [int]$InitialWait = 30,
    [int]$PollInterval = 15,
    [int]$MaxWait = 1200,
    # Copilot code review publishes its own check run under this name.
    [string]$CopilotCheck = 'copilot-pull-request-reviewer',
    # Immutable GitHub user id of the genuine Copilot code review bot. Matching
    # on this id (plus type 'Bot') rather than the login prevents a human from
    # impersonating Copilot with a look-alike username. The same id appears in
    # the reviews API (login 'copilot-pull-request-reviewer[bot]') and the
    # timeline (login 'Copilot').
    [long]$CopilotBotId = 175728472,
    # GitHub Actions app id - Copilot's review check run is posted by it.
    [int]$ActionsAppId = 15368
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Status of Copilot's review check on the current head commit:
# queued / in_progress / completed / none. Require the check to come from the
# GitHub Actions app (which posts Copilot's review check) as defense-in-depth.
function Get-CopilotCheckStatus {
    $resp = gh api "repos/$Repo/commits/$HeadSha/check-runs?per_page=100" | ConvertFrom-Json
    $runs = @($resp.check_runs | Where-Object {
            $_.name -eq $CopilotCheck -and $_.app -and $_.app.id -eq $ActionsAppId
        } | Sort-Object started_at)
    if ($runs.Count -eq 0) { return 'none' }
    return $runs[-1].status
}

# Genuine Copilot reviews, matched on the bot's immutable user id + type 'Bot'
# (NOT the login, which is spoofable by a look-alike username).
function Get-CopilotReview {
    $reviews = gh api "repos/$Repo/pulls/$Pr/reviews?per_page=100" | ConvertFrom-Json
    @($reviews | Where-Object {
            $_.user -and $_.user.id -eq $CopilotBotId -and $_.user.type -eq 'Bot' -and $_.state -ne 'PENDING'
        })
}

# Has Copilot already submitted a (non-pending) review for THIS head?
function Test-CopilotReviewedHead {
    @(Get-CopilotReview | Where-Object { $_.commit_id -eq $HeadSha }).Count -gt 0
}

# Is there a Copilot review request not yet answered by a submitted review?
# (latest "review_requested" for Copilot newer than its latest submitted review).
# The requested_reviewers endpoint does not list Copilot, so we use the issue
# timeline events instead, matched on the bot's immutable user id + type 'Bot'.
function Test-CopilotRequestOutstanding {
    $timeline = gh api "repos/$Repo/issues/$Pr/timeline?per_page=100" | ConvertFrom-Json
    $reqTimes = @($timeline | Where-Object {
            $_.event -eq 'review_requested' -and $_.requested_reviewer -and
            $_.requested_reviewer.id -eq $CopilotBotId -and $_.requested_reviewer.type -eq 'Bot'
        } | ForEach-Object { [datetime]$_.created_at })
    if ($reqTimes.Count -eq 0) { return $false }
    $lastReq = ($reqTimes | Measure-Object -Maximum).Maximum

    $revTimes = @(Get-CopilotReview | ForEach-Object { [datetime]$_.submitted_at })
    if ($revTimes.Count -eq 0) { return $true }
    $lastRev = ($revTimes | Measure-Object -Maximum).Maximum
    return $lastReq -gt $lastRev
}

# Should the gate keep waiting for Copilot on this head commit?
function Test-ShouldWait {
    switch (Get-CopilotCheckStatus) {
        'queued' { return $true }
        'in_progress' { return $true }
        'completed' { return $false }
    }
    # No check run yet for this head:
    if (Test-CopilotReviewedHead) { return $false }
    return [bool](Test-CopilotRequestOutstanding)
}

Write-Host "Waiting ${InitialWait}s for Copilot to pick up commit $HeadSha..."
Start-Sleep -Seconds $InitialWait

if (-not (Test-ShouldWait)) {
    Write-Host 'Copilot is not reviewing this commit; gate passes.'
    exit 0
}

Write-Host 'Copilot review in progress or pending; waiting for it to finish...'
$elapsed = 0
while (Test-ShouldWait) {
    if ($elapsed -ge $MaxWait) {
        Write-Host "::warning::Timed out after ${MaxWait}s waiting for Copilot; passing to avoid a stuck PR."
        exit 0
    }
    Start-Sleep -Seconds $PollInterval
    $elapsed += $PollInterval
}

Write-Host "Copilot review finished for $HeadSha; gate passes."
