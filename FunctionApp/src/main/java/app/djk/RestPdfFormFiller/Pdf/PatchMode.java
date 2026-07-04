package app.djk.RestPdfFormFiller.Pdf;

/**
 * Describes how a <em>provided</em> value interacts with the existing value at the same field during a
 * {@link WriteMode#PATCH} fill. It has no effect under {@link WriteMode#PUT}, which always overwrites.
 * <p>
 * This is the collision policy for a merge: {@link #OVERWRITE} lets the provided value win, {@link #IF_EMPTY}
 * protects any value already present, and {@link #FAIL_ON_CONFLICT} refuses to silently replace a different
 * existing value. It is the optional companion to {@link WriteMode}: absent from a request, it defaults to
 * {@link #OVERWRITE}.
 */
public enum PatchMode {
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

    PatchMode(final String value) {
        this.value = value;
    }

    /**
     * @return The wire value used in request payloads.
     */
    public String value() {
        return value;
    }

    /**
     * Resolves a wire value to its {@link PatchMode}.
     *
     * @param value The request wire value.
     * @return The matching mode, or <code>null</code> if the value is not recognized.
     */
    public static PatchMode fromValue(final String value) {
        for (final var mode : values()) {
            if (mode.value.equals(value)) {
                return mode;
            }
        }
        return null;
    }
}
