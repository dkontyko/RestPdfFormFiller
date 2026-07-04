package app.djk.RestPdfFormFiller.Pdf;

import app.djk.RestPdfFormFiller.projectExceptions.InvalidXfaFormException;
import app.djk.RestPdfFormFiller.projectExceptions.WriteConflictException;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.PdfStamper;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerException;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.util.List;

public class RestPdfApi {

    private RestPdfApi() {
        throw new IllegalStateException("Utility class");
    }
    public static final List<String> FORM_DATA_FORMATS = List.of("json", "xml");

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

            final var transformer = SecureTransformerFactory.newInstance().newTransformer();
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
        try (var pdfReader = new PdfReader(pdfStream)) {
            return pdfReader.getAcroFields().getXfa().isXfaPresent();
        }
    }

    public static boolean isXfaForm(final byte[] pdfBytes) throws IOException {
        return isXfaForm(new ByteArrayInputStream(pdfBytes));
    }


    /**
     * Convenience overload that reads the entire stream into memory before filling.
     * <p>
     * The underlying openpdf APIs (<code>PdfReader</code>/<code>PdfStamper</code>) and the PATCH merge both
     * need to traverse and, in the conflict case, re-read the document, so the bytes are fully buffered here
     * rather than streamed. Callers that already hold a stream (rather than a byte array) can use this without
     * having to buffer the content themselves.
     *
     * @param pdfStream    Source XFA PDF content. The stream is fully consumed but not closed by this method.
     * @param jsonFormData JSON object string of the form <code>{"data": { ... }}</code>.
     * @return The filled PDF as a byte array.
     * @throws IOException                  If the stream cannot be read or the PDF cannot be parsed.
     * @throws ParserConfigurationException If the JSON-to-XML conversion cannot create an XML document.
     * @throws SAXException                 If the converted form data cannot be parsed as XML.
     */
    public static byte[] fillXfaForm(final InputStream pdfStream, final String jsonFormData) throws IOException, ParserConfigurationException, SAXException {
        return fillXfaForm(pdfStream.readAllBytes(), jsonFormData);
    }

    /**
     * Convenience overload that fills using the default strategy: {@link WriteMode#PATCH} with
     * {@link PatchMode#OVERWRITE}.
     * <p>
     * These defaults match the historical, least-surprising contract: the request is merged into the existing form
     * (nothing the caller omits is touched) and, for the fields the caller does provide, whatever it supplies wins.
     * This overload exists so that callers (and tests) that do not need to choose a strategy are not forced to pass
     * the mode arguments, keeping the common case terse while still routing through the single, authoritative fill
     * implementation.
     *
     * @param pdfBytes     Source XFA PDF content.
     * @param jsonFormData JSON object string of the form <code>{"data": { ... }}</code>.
     * @return The filled PDF as a byte array.
     * @throws InvalidXfaFormException      If the PDF is not an XFA form.
     * @throws IOException                  If the PDF cannot be parsed.
     * @throws ParserConfigurationException If the JSON-to-XML conversion cannot create an XML document.
     * @throws SAXException                 If the converted form data cannot be parsed as XML.
     */
    public static byte[] fillXfaForm(final byte[] pdfBytes, final String jsonFormData) throws IOException, ParserConfigurationException, SAXException {
        return fillXfaForm(pdfBytes, jsonFormData, WriteMode.PATCH, PatchMode.OVERWRITE);
    }

    /**
     * Fills an XFA form with the provided data.
     * <p>
     * The two selectors are orthogonal:
     * <ul>
     *   <li>{@link WriteMode} chooses the overall strategy. {@link WriteMode#PATCH} merges the request into the
     *       existing form (omitted fields are preserved); {@link WriteMode#PUT} replaces the whole form with the
     *       request (omitted fields end up blank).</li>
     *   <li>{@link PatchMode} is the collision policy for the fields that <em>are</em> provided, and applies only
     *       under {@link WriteMode#PATCH}. Under {@link WriteMode#PUT} it is irrelevant because a full replacement
     *       never merges against an existing value.</li>
     * </ul>
     * <p>
     * <strong>Why the implementation looks the way it does.</strong> Two openpdf behaviors drive the design:
     * <ul>
     *   <li><em>Write-back requires the live form.</em> Only the <code>XfaForm</code> obtained from the stamper's
     *       <code>AcroFields.getXfa()</code> is serialized back into the document when the stamper closes. A
     *       detached <code>new XfaForm(reader)</code> can be modified but its changes are silently dropped, so we
     *       deliberately fetch the form via the stamper.</li>
     *   <li><em>{@code fillXfaForm} replaces the whole data subtree.</em> openpdf swaps out the entire
     *       <code>&lt;xfa:data&gt;</code> form-root rather than merging field by field. That makes {@code PUT} a
     *       direct hand-off of the incoming data. For {@code PATCH}, however, any field missing from the node we
     *       hand it would be <em>erased</em> rather than left alone, so we first merge the incoming values onto a
     *       copy of the template's existing data (see {@link #mergeFormData}) and pass that complete subtree.</li>
     * </ul>
     * The XFA presence check happens up front so that a non-XFA PDF fails fast with a caller-safe error before we
     * open a stamper.
     *
     * @param pdfBytes     Source XFA PDF content.
     * @param jsonFormData JSON object string of the form <code>{"data": { ... }}</code>.
     * @param writeMode    Whether to merge ({@link WriteMode#PATCH}) or fully replace ({@link WriteMode#PUT}).
     * @param patchMode    Collision policy for provided fields under {@link WriteMode#PATCH}; ignored for
     *                     {@link WriteMode#PUT}.
     * @return The filled PDF as a byte array.
     * @throws InvalidXfaFormException      If the PDF is not an XFA form.
     * @throws WriteConflictException       If <code>writeMode</code> is {@link WriteMode#PATCH}, <code>patchMode</code>
     *                                      is {@link PatchMode#FAIL_ON_CONFLICT}, and a provided value would overwrite
     *                                      a different, non-empty existing value.
     * @throws IOException                  If the PDF cannot be parsed or stamped.
     * @throws ParserConfigurationException If the JSON-to-XML conversion cannot create an XML document.
     * @throws SAXException                 If the converted form data cannot be parsed as XML.
     */
    public static byte[] fillXfaForm(final byte[] pdfBytes, final String jsonFormData, final WriteMode writeMode,
                                     final PatchMode patchMode)
            throws IOException, ParserConfigurationException, SAXException {
        if (!isXfaForm(pdfBytes)) throw new InvalidXfaFormException();

        final var outputStream = new ByteArrayOutputStream();
        try (final var reader = new PdfReader(pdfBytes);
             final var pdfStamper = new PdfStamper(reader, outputStream)) {

            // Using the AcroFields' live XFA form (rather than a detached copy) ensures the changes are
            // written back to the PDF when the stamper is closed.
            final var xfaForm = pdfStamper.getAcroFields().getXfa();

            // Incoming shape: <xfa:datasets><xfa:data><formRoot>...  ->  formRoot element.
            final var incomingDoc = DataFormatter.convertJsonToXml(jsonFormData);
            final var incomingFormRoot = firstElementChild(firstElementChild(incomingDoc.getDocumentElement()));

            if (incomingFormRoot != null) {
                // PUT replaces the entire form, which is exactly what openpdf's fillXfaForm does with the incoming
                // data, so PATCH is the only mode that needs to merge against (and thus preserve) existing values.
                final Node dataToWrite;
                if (writeMode == WriteMode.PUT) {
                    dataToWrite = incomingFormRoot;
                } else {
                    final var existingFormRoot = firstElementChild(xfaForm.getDatasetsNode().getFirstChild());
                    dataToWrite = mergeFormData(existingFormRoot, incomingFormRoot, patchMode);
                }
                xfaForm.fillXfaForm(dataToWrite);
            }
        }
        return outputStream.toByteArray();
    }

    /**
     * Builds the data subtree to write into the form during a {@link WriteMode#PATCH}: a copy of the template's
     * existing data with the incoming values applied according to <code>patchMode</code>. Fields absent from the
     * incoming data are preserved.
     * <p>
     * <strong>Why we start from a clone of the existing data.</strong> Because openpdf's
     * <code>fillXfaForm</code> replaces the entire form-root, the node we return must already contain the full,
     * final state of the form. Cloning the template's current data gives us every existing field for free; we then
     * overlay only the incoming values. This is also what makes the patch modes expressible: the merge can compare
     * each incoming value against the existing one and decide whether to keep, replace, or reject it.
     * <p>
     * The clone (a deep copy) is used rather than mutating the live data node directly so that a
     * {@link WriteConflictException} thrown partway through leaves the document untouched &mdash; the caller either
     * gets a fully merged result or none at all.
     *
     * @param existingFormRoot The template's current data form-root, or <code>null</code> if the template has no
     *                         data yet.
     * @param incomingFormRoot The form-root parsed from the caller's JSON payload.
     * @param patchMode        The per-field collision policy to apply.
     * @return The form-root node to hand to <code>fillXfaForm</code>.
     */
    private static Node mergeFormData(final Element existingFormRoot, final Element incomingFormRoot,
                                      final PatchMode patchMode) {
        if (existingFormRoot == null) {
            // No existing data subtree to merge against; write the incoming data as-is.
            return incomingFormRoot;
        }
        final var mergedFormRoot = existingFormRoot.cloneNode(true);
        applyIncoming(incomingFormRoot, mergedFormRoot, patchMode, localName(incomingFormRoot));
        return mergedFormRoot;
    }

    /**
     * Recursively overlays the incoming data tree onto the merge base, applying the {@link PatchMode} policy at each
     * leaf. The base is mutated in place; containers are matched by element name so that the two trees are walked in
     * parallel.
     * <p>
     * <strong>Why it recurses and matches by name.</strong> XFA data is a nested tree (for example
     * <code>form1 &rarr; Page1 &rarr; SSN</code>), and field identity is positional-by-name within that hierarchy,
     * not a flat key. Walking both trees together lets us line up each incoming field with its existing counterpart
     * so the patch mode can be evaluated against the correct current value. The running <code>path</code> is carried
     * purely so that a conflict can report <em>which</em> field failed (for example <code>form1/Page1/SSN</code>)
     * without ever exposing the field's value.
     * <p>
     * Nodes that exist only in the incoming payload are added wholesale: a brand-new field has no existing value, so
     * every patch mode treats it as a plain insert and no conflict is possible.
     *
     * @param incomingParent The current node in the caller-supplied tree being copied from.
     * @param baseParent     The corresponding node in the merge base being written to.
     * @param patchMode      The per-field collision policy to apply.
     * @param path           Slash-delimited field path to <code>incomingParent</code>, used only for conflict
     *                       reporting.
     * @throws WriteConflictException If <code>patchMode</code> is {@link PatchMode#FAIL_ON_CONFLICT} and a leaf value
     *                                differs from a non-empty existing value.
     */
    private static void applyIncoming(final Node incomingParent, final Node baseParent,
                                      final PatchMode patchMode, final String path) {
        final var incomingChildren = incomingParent.getChildNodes();
        for (int i = 0; i < incomingChildren.getLength(); i++) {
            final var node = incomingChildren.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            final var incomingChild = (Element) node;
            final var name = localName(incomingChild);
            final var childPath = path.isEmpty() ? name : path + "/" + name;
            final var baseDoc = baseParent.getOwnerDocument();

            if (hasElementChild(incomingChild)) {
                final var baseChild = findChildElement(baseParent, name);
                if (baseChild == null) {
                    // Brand-new subtree with no existing counterpart: import it wholesale.
                    baseParent.appendChild(baseDoc.importNode(incomingChild, true));
                } else {
                    applyIncoming(incomingChild, baseChild, patchMode, childPath);
                }
                continue;
            }

            final var incomingValue = textValue(incomingChild);
            final var baseChild = findChildElement(baseParent, name);
            if (baseChild == null) {
                // New leaf field with no existing value: apply the incoming value regardless of patch mode.
                final var created = baseDoc.createElement(name);
                created.setTextContent(incomingValue);
                baseParent.appendChild(created);
                continue;
            }

            final var existingValue = textValue(baseChild);
            switch (patchMode) {
                case OVERWRITE -> baseChild.setTextContent(incomingValue);
                case IF_EMPTY -> {
                    if (existingValue.isEmpty()) {
                        baseChild.setTextContent(incomingValue);
                    }
                }
                case FAIL_ON_CONFLICT -> {
                    if (!existingValue.isEmpty() && !existingValue.equals(incomingValue)) {
                        throw new WriteConflictException(childPath);
                    }
                    baseChild.setTextContent(incomingValue);
                }
            }
        }
    }

    /**
     * Reports whether the node has at least one child element.
     * <p>
     * The merge needs to distinguish <em>containers</em> (which it recurses into) from <em>leaves</em> (which hold a
     * field value to write). A node is treated as a container as soon as it has any element child, mirroring how the
     * XFA data tree nests fields; text and whitespace nodes are ignored so that indentation in the source XML does
     * not accidentally make a leaf look like a container.
     *
     * @param node The node to inspect.
     * @return <code>true</code> if <code>node</code> has any element child, otherwise <code>false</code>.
     */
    private static boolean hasElementChild(final Node node) {
        final var children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the first child element of <code>node</code>, skipping non-element nodes.
     * <p>
     * This is how we descend the fixed XFA envelope (<code>&lt;xfa:datasets&gt; &rarr; &lt;xfa:data&gt; &rarr;
     * form-root</code>) and locate a form-root on both the incoming and existing sides. The DOM's own
     * <code>getFirstChild()</code> can return a whitespace text node when the XML is pretty-printed, which would
     * break that descent, so we explicitly skip to the first element. Returning <code>null</code> (rather than
     * throwing) lets callers treat "no data present" as a normal case &mdash; for example a template that has not
     * been filled yet.
     *
     * @param node The parent node, or <code>null</code>.
     * @return The first element child, or <code>null</code> if <code>node</code> is <code>null</code> or has none.
     */
    private static Element firstElementChild(final Node node) {
        if (node == null) {
            return null;
        }
        final var children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            final var child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                return (Element) child;
            }
        }
        return null;
    }

    /**
     * Finds the first child element of <code>parent</code> whose name matches <code>name</code>.
     * <p>
     * This is the parallel-walk lookup that pairs an incoming field with its existing counterpart in the merge base.
     * Matching is done on {@link #localName(Node) local name} so that namespace-prefix differences between the two
     * documents do not prevent a match. A <code>null</code> result means the field does not yet exist in the base,
     * which the merge treats as a plain insert.
     *
     * @param parent The container whose children are searched.
     * @param name   The (local) element name to match.
     * @return The matching child element, or <code>null</code> if none matches.
     */
    private static Element findChildElement(final Node parent, final String name) {
        final var children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            final var child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && localName(child).equals(name)) {
                return (Element) child;
            }
        }
        return null;
    }

    /**
     * Returns the namespace-independent name of a node.
     * <p>
     * The incoming data (built programmatically) and the existing data (parsed from the PDF) can disagree on whether
     * elements carry a namespace, so their {@link Node#getLocalName() local names} may be <code>null</code> in one
     * tree and populated in the other. Falling back to {@link Node#getNodeName()} gives a single, stable key for
     * matching fields across both trees regardless of how each was constructed.
     *
     * @param node The node to name.
     * @return The node's local name, or its qualified node name when no local name is available.
     */
    private static String localName(final Node node) {
        final var localName = node.getLocalName();
        return localName != null ? localName : node.getNodeName();
    }

    /**
     * Returns the trimmed text content of a node, or an empty string when it has none.
     * <p>
     * Patch-mode decisions hinge on whether a target is "empty" and on comparing the incoming value to the existing
     * one, so the values must be normalized first. Trimming ensures that insignificant surrounding whitespace (often
     * introduced by XML formatting) neither makes an otherwise-empty field look populated nor causes two equal
     * values to register as a conflict. Guarding against <code>null</code> keeps the comparisons total.
     *
     * @param node The node whose value is read.
     * @return The trimmed text content, never <code>null</code>.
     */
    private static String textValue(final Node node) {
        final var text = node.getTextContent();
        return text == null ? "" : text.trim();
    }
}
