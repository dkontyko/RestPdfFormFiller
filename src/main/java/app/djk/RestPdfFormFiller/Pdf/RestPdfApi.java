package app.djk.RestPdfFormFiller.Pdf;

import app.djk.RestPdfFormFiller.projectExceptions.InvalidXfaFormException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.PdfStamper;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;

public class RestPdfApi {
    public static final ArrayList<String> FORM_DATA_FORMATS = new ArrayList<>(Arrays.asList("json", "xml"));

    /**
     * Gets the XML form field data from the given DA 4187. (This may work with other XFA forms, but
     * it's specifically designed to work with the 4187 for now.)
     *
     * @param is An <code>InputStream</code> representing the DA 4187.
     * @return A pretty-printed XML String of the XFA form data (everything withing and including the datasets node).
     * @throws IOException          If there's a problem with creating the <code>PDFReader</code>.
     * @throws TransformerException If there's a problem with transforming the extracted datasets node into a string.
     */
    public static String getXfaDatasetNodeAsString(InputStream is) throws IOException, TransformerException {
        try (var newReader = new PdfReader(is)) {
            //This is the node that contains the XFA form data.
            final var datasetsNode = newReader.getAcroFields().getXfa().getDatasetsNode();

            final var transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");

            var strWriter = new StringWriter();

            // Copying the datasets node into a pretty-printed StringWriter object.
            transformer.transform(new DOMSource(datasetsNode), new StreamResult(strWriter));

            return strWriter.toString();
        }
    }

    /**
     * Wraps the <code>byte[]</code> in an <code>InputStream</code> and calls
     * <code>getXfaDatasetNodeAsString(InputStream is)</code>.
     *
     * @param pdfBytes A <code>byte[]</code> representing the DA 4187.
     * @return Same as the overloaded method.
     * @throws IOException          Same as the overloaded method.
     * @throws TransformerException Same as the overloaded method.
     */
    public static String getXfaDatasetNodeAsString(byte[] pdfBytes) throws IOException, TransformerException {
        return getXfaDatasetNodeAsString(new ByteArrayInputStream(pdfBytes));
    }

    /*
    public static byte[] setXfaDatasetNode(InputStream inputStream, String xmlDataset) throws IOException {
        try(var reader = new PdfReader(inputStream))  {

        }
    }
     */



    public static boolean isXfaForm(final InputStream pdfStream) throws IOException {
        try(var pdfReader = new PdfReader(pdfStream)) {
            return pdfReader.getAcroFields().getXfa().isXfaPresent();
        }
    }

    public static boolean isXfaForm(final byte[] pdfBytes) throws IOException {
        return isXfaForm(new ByteArrayInputStream(pdfBytes));
    }

    //TODO Given JSON content for specified form, get PDF with form fields filled with content

    /*
    public static OutputStream fillXfaForm(final InputStream pdfStream, final String jsonFormData) throws IOException {
        if(!isXfaForm(pdfStream)) throw new InvalidXfaFormException();
        var outFile = new BufferedOutputStream(new ByteArrayOutputStream());
        try {
            var pdfStamper = new PdfStamper(new PdfReader(pdfStream), outFile);
            var objMapper = new ObjectMapper();
            var jsonNode = objMapper.readTree(jsonFormData);
            // validate JSON
            // put JSON into form
            // return OutputStream of PDF
        }
    }

     */
}
