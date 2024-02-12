package app.djk.RestPdfFormFiller.Pdf;

import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerFactory;

public class SecureTransformerFactory {

    private SecureTransformerFactory() {
        throw new IllegalStateException("Utility class");
    }
    public static TransformerFactory newInstance() throws TransformerConfigurationException {
        TransformerFactory factory = TransformerFactory.newInstance(); //NOSONAR
        try {
            // Disable external entities
            factory.setFeature("http://javax.xml.XMLConstants/feature/secure-processing", true);
            factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "");
            factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalStylesheet", "");
        } catch (TransformerConfigurationException e) {
            throw new TransformerConfigurationException("Could not configure TransformerFactory for secure processing: " + e.getMessage());
        }
        return factory;
    }

}
