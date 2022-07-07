# RestPdfFormFiller

[![Azure Function App Deployment](https://github.com/dkontyko/RestPdfFormFiller/actions/workflows/main_restpdfformfiller(dev).yml/badge.svg)](https://github.com/dkontyko/RestPdfFormFiller/actions/workflows/main_restpdfformfiller(dev).yml)

[![CodeQL](https://github.com/dkontyko/RestPdfFormFiller/actions/workflows/codeql.yml/badge.svg)](https://github.com/dkontyko/RestPdfFormFiller/actions/workflows/codeql.yml)

[![Java CI with Maven](https://github.com/dkontyko/RestPdfFormFiller/actions/workflows/maven.yml/badge.svg)](https://github.com/dkontyko/RestPdfFormFiller/actions/workflows/maven.yml)

# Project Structure
## HttpTriggerFunctions
Holds the  Azure Function App endpoints. Handles HTTP requests and builds responses.

## RestPdfApi
Holds the static methods that perform the actual PDF operations.

# Azure Function Endpoints
## GetXfaData (POST)
Given an XFAF PDF form, extracts and returns the datasets node as either XML or JSON.

### Parameters
* format: Query parameter that must be either "xml" or "json".
* bodyData: The POST body must be the base64-encoded bytes of the PDF file. For Power Automate, you can use the SharePoint Get file content action and reference body/$content.

## Fill Form (Not implemented)
Given a PDF form and a JSON object of field values, returns the PDF form with the given fields containing the values passed in the JSON object.
