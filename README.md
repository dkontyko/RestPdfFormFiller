# RestPdfFormFiller

[![Azure Function App Deployment](https://github.com/dkontyko/RestPdfFormFiller/actions/workflows/main_restpdfformfiller(dev).yml/badge.svg)](https://github.com/dkontyko/RestPdfFormFiller/actions/workflows/main_restpdfformfiller(dev).yml)

[![CodeQL](https://github.com/dkontyko/RestPdfFormFiller/actions/workflows/codeql.yml/badge.svg)](https://github.com/dkontyko/RestPdfFormFiller/actions/workflows/codeql.yml)

[![Java CI with Maven](https://github.com/dkontyko/RestPdfFormFiller/actions/workflows/maven.yml/badge.svg)](https://github.com/dkontyko/RestPdfFormFiller/actions/workflows/maven.yml)

## Function App Structure
### HttpTriggerFunctions
Holds the  Azure Function App endpoints. Handles HTTP requests and builds responses.

### RestPdfApi
Holds the static methods that perform the actual PDF operations.

## Azure Function Endpoints
### GetXfaData (HTTP POST)
Given an XFAF PDF form, extracts and returns the datasets node as either XML or JSON.

#### Parameters
* format: Query parameter that must be either "xml" or "json".
* bodyData: The POST body must contain the raw bytes of the PDF file.

### GetXfaSchema (HTTP POST)
Given an XFAF PDF form (same at GetXfaData), returns the basic XML schema of the form.

#### Power Automate PDF input
The `GetXfaData` and `GetXfaFormSchema` custom-connector actions apply a request policy that sends PDF input as `application/octet-stream`. When the file comes from a SharePoint or OneDrive **Get file content** action, pass its **File Content** output directly; no conversion expression is required.

### Fill Form (Not implemented)
Given a PDF form and a JSON object of field values, returns the PDF form with the given fields containing the values passed in the JSON object.
