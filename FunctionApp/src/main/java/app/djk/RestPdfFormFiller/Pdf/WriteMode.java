package app.djk.RestPdfFormFiller.Pdf;

/**
 * Describes how provided values interact with existing target values when filling an XFA form.
 */
public enum WriteMode {
    /**
     * Provided values replace existing target values.
     */
    OVERWRITE("overwrite"),
    /**
     * Provided values are applied only when the existing target value is empty.
     */
    IF_EMPTY("ifEmpty"),
    /**
     * Provided values that conflict with a different, non-empty existing target value are rejected.
     */
    FAIL_ON_CONFLICT("failOnConflict");

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
