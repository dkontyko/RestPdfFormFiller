# Define the Maven command to check for updates
$mavenDisplayDependenciesCmd = "mvn versions:display-dependency-updates -DprocessDependencyManagement=true -DgenerateBackupPoms=false -f FunctionApp/pom.xml"

# Run the Maven command and capture the output
$output = Invoke-Expression $mavenDisplayDependenciesCmd

# Parse the output to find any updated dependencies
$updatedDependencies = ($output | Select-String " -> " | ForEach-Object { $_.Line -replace " -> ", "" })

# If there are no updated dependencies, exit the script
if ($updatedDependencies.Count -eq 0) {
    Write-Host "No updated dependencies found."
    exit
}

# Create a new branch for the dependency updates
$branchName = "update-dependencies-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
git checkout -b $branchName

mvn versions:use-latest-versions -DgenerateBackupPoms=false -f FunctionApp/pom.xml

git config user.email "Actions@github.com"
git config user.name "GitHub Actions"

# Commit the changes to the branch
git add FunctionApp/pom.xml
git commit -m "Update dependencies"

# Push the branch to the remote repository
git push -u origin $branchName

# Create a pull request to merge the changes into the main branch
$prTitle = "Update dependencies"
$prBody = "This pull request updates the following dependencies:`n`n$($updatedDependencies -join "`n")"
gh pr create --title $prTitle --body $prBody --base main --head $branchName --assignee dkontyko