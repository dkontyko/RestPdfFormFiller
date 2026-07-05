using './main.bicep'

// Dev-environment values — mirror the live resources in RG PdfFormFiller.
param location = 'centralus'
param storageAccountName = 'pdfformfilleraf71'
param workspaceName = 'PdfFormFillerLogs'
param appInsightsName = 'RestPdfFormFiller'
param planName = 'ASP-PdfFormFiller-a3cf'
param functionAppName = 'RestPdfFormFiller'
param slotName = 'dev'
param prodContentShareName = 'restpdfformfillerbc13'
param devContentShareName = 'restpdfformfiller-d7e1ae2f'
param functionDeployIdentityName = 'gh-restpdfformfiller-deploy'
param connectorDeployIdentityName = 'gh-customconnector-deploy'
param githubRepo = 'dkontyko/RestPdfFormFiller'
param functionDeployEnvironment = 'azure-dev'
param connectorDeployEnvironment = 'power-platform-dev'
param retentionInDays = 31
param dailyQuotaGb = 1

// Entra-only (managed-identity) ingestion is the steady state (key-based auth
// disabled). If telemetry ever stops after an identity or permission change,
// set this to `false` temporarily to re-enable key-based ingestion while
// debugging, then flip it back to `true`.
param enforceEntraOnlyIngestion = true

param tags = {
  workload: 'RestPdfFormFiller'
  environment: 'dev'
  managedBy: 'bicep'
}
