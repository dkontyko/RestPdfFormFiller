# RestPdfFormFiller

![Azure Function Deploy (Dev)](https://github.com/dkontyko/RestPdfFormFiller/actions/workflows/dev_restpdfformfiller.yml/badge.svg)

![CodeQL (Dev)](https://github.com/dkontyko/RestPdfFormFiller/actions/workflows/codeql.yml/badge.svg)

![Java CI with Maven (Dev)](https://github.com/dkontyko/RestPdfFormFiller/actions/workflows/maven.yml/badge.svg)

# Project Structure
## HttpTriggerFunctions
Holds the actual Azure functions that will be exposed to the internet. Performs HTTP/REST operations.

## RestPdfApi
Sends and receives (something) to the trigger functions above. Interfaces with OpenPDF to perform the requested operations.


# (Future) API
## Get Form Fields
Given a PDF form (XFA or regular Acroform), retreives a list of form fields correlated with their field descriptions/numbering.

## Fill Form
Given a PDF form and a JSON object of field values, returns the PDF form with the given fields containing the values passed in the JSON object.
