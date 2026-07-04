package app.djk.RestPdfFormFiller.Pdf;

/**
 * Describes the overall fill strategy: whether the request is a partial update or a full replacement.
 * <p>
 * This is the "PATCH vs PUT" distinction for form data. {@link #PATCH} merges the request into the existing form,
 * keeping any field the caller did not mention and applying the optional {@link PatchMode} collision policy to the
 * fields that are provided. {@link #PUT} ignores the existing data entirely and replaces the whole form with the
 * request, so any field the caller omits ends up blank.
 */
public enum WriteMode {
    /**
     * Merge the request into the existing form: omitted fields are preserved and provided fields are governed by the
     * accompanying {@link PatchMode}.
     */
    PATCH("patch"),
    /**
     * Replace the entire form with the request: provided fields are written and omitted fields are cleared. Any
     * {@link PatchMode} is irrelevant because there is nothing to merge against.
     */
    PUT("put");

    private final String value;

    WriteMode(final String value) {
        this.value = value;
    }

    /**
     * @return The wire value used in request payloads.
     */
    public String value() {
        return value;
    }

    /**
     * Resolves a wire value to its {@link WriteMode}.
     *
     * @param value The request wire value.
     * @return The matching mode, or <code>null</code> if the value is not recognized.
     */
    public static WriteMode fromValue(final String value) {
        for (final var mode : values()) {
            if (mode.value.equals(value)) {
                return mode;
            }
        }
        return null;
    }
}
