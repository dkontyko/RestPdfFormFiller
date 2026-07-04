package app.djk.RestPdfFormFiller.Pdf;

/**
 * Describes how complete the caller's payload is, which determines what happens to fields that exist in the
 * template but are <em>absent</em> from the request.
 * <p>
 * This is the "PATCH vs PUT" distinction for form data: {@link #PARTIAL} treats the payload as a patch and leaves
 * unmentioned fields untouched, whereas {@link #COMPLETE} treats the payload as the full intended dataset and clears
 * unmentioned fields. It is orthogonal to {@link WriteMode}, which governs only how <em>provided</em> values interact
 * with existing ones.
 */
public enum PayloadMode {
    /**
     * The payload is a subset of fields to patch; fields omitted from the request keep their existing values.
     */
    PARTIAL("partial"),
    /**
     * The payload is the complete intended dataset; fields omitted from the request are cleared.
     */
    COMPLETE("complete");

    private final String value;

    PayloadMode(final String value) {
        this.value = value;
    }

    /**
     * @return The wire value used in request payloads.
     */
    public String value() {
        return value;
    }

    /**
     * Resolves a wire value to its {@link PayloadMode}.
     *
     * @param value The request wire value.
     * @return The matching mode, or <code>null</code> if the value is not recognized.
     */
    public static PayloadMode fromValue(final String value) {
        for (final var mode : values()) {
            if (mode.value.equals(value)) {
                return mode;
            }
        }
        return null;
    }
}
