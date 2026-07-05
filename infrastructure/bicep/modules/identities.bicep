// ---------------------------------------------------------------------------
// identities.bicep — The two user-assigned managed identities that GitHub
// Actions uses to deploy, plus their GitHub OIDC federated credentials (FICs).
//
//   - functionDeployIdentity  (gh-restpdfformfiller-deploy): deploys the
//     Function App. FIC subject is ENVIRONMENT-scoped (repo:<repo>:environment:
//     <env>) because the deploy workflow declares `environment: azure-dev`.
//     This REPLACES the former branch-scoped (ref:refs/heads/main) credential.
//   - connectorDeployIdentity (gh-customconnector-deploy): deploys the Power
//     Platform custom connector. FIC subject is environment:power-platform-dev.
//     This identity carries NO Azure RBAC — its access is via a Dataverse
//     application user (see scripts/configure-dataverse.ps1).
// ---------------------------------------------------------------------------

@description('Azure region for the managed identities.')
param location string

@description('Name of the UAMI that deploys the Function App.')
param functionDeployIdentityName string

@description('Name of the UAMI that deploys the Power Platform custom connector.')
param connectorDeployIdentityName string

@description('GitHub repository in owner/name form, e.g. dkontyko/RestPdfFormFiller.')
param githubRepo string

@description('GitHub environment that gates the Function App deploy (drives the FIC subject).')
param functionDeployEnvironment string

@description('GitHub environment that gates the connector deploy (drives the FIC subject).')
param connectorDeployEnvironment string

@description('Tags applied to the identities.')
param tags object = {}

var issuer = 'https://token.actions.githubusercontent.com'
var audiences = [
  'api://AzureADTokenExchange'
]

resource functionDeploy 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' = {
  name: functionDeployIdentityName
  location: location
  tags: tags
}

resource functionDeployFic 'Microsoft.ManagedIdentity/userAssignedIdentities/federatedIdentityCredentials@2023-01-31' = {
  parent: functionDeploy
  name: 'github-env-${functionDeployEnvironment}'
  properties: {
    issuer: issuer
    subject: 'repo:${githubRepo}:environment:${functionDeployEnvironment}'
    audiences: audiences
  }
}

resource connectorDeploy 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' = {
  name: connectorDeployIdentityName
  location: location
  tags: tags
}

resource connectorDeployFic 'Microsoft.ManagedIdentity/userAssignedIdentities/federatedIdentityCredentials@2023-01-31' = {
  parent: connectorDeploy
  name: 'github-env-${connectorDeployEnvironment}'
  properties: {
    issuer: issuer
    subject: 'repo:${githubRepo}:environment:${connectorDeployEnvironment}'
    audiences: audiences
  }
}

output functionDeployPrincipalId string = functionDeploy.properties.principalId
output functionDeployClientId string = functionDeploy.properties.clientId
output connectorDeployPrincipalId string = connectorDeploy.properties.principalId
output connectorDeployClientId string = connectorDeploy.properties.clientId
