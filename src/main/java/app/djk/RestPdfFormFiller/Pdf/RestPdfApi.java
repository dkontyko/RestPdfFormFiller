package app.djk.RestPdfFormFiller.Pdf;

import com.lowagie.text.pdf.PdfReader;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;

public class RestPdfApi {

    /**
     * Gets the XML form field data from the given DA 4187. (This may work with other XFA forms, but
     * it's specifically designed to work with the 4187 for now.
     * @param is An <code>InputStream</code> representing the DA 4187.
     * @return A pretty-printed XML String of the XFA form data.
     * @throws IOException If there's a problem with creating the <code>PDFReader</code>.
     * @throws TransformerException If there's a problem with transforming the extracted datasets node into a string.
     */
    public static String get4187DatasetNodeAsString(InputStream is) throws IOException, TransformerException {
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
     * <code>get4187DatasetNodeAsString(InputStream is)</code>.
     * @param pdfBytes A <code>byte[]</code> representing the DA 4187.
     * @return Same as the overloaded method.
     * @throws IOException Same as the overloaded method.
     * @throws TransformerException Same as the overloaded method.
     */
    public static String get4187DatasetNodeAsString(byte[] pdfBytes) throws IOException, TransformerException {
        return get4187DatasetNodeAsString(new ByteArrayInputStream(pdfBytes));
    }

    //TODO Given JSON content for specified form, get PDF with form fields filled with content
}
