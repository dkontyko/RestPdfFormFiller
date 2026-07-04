package app.djk.RestPdfFormFiller.projectExceptions;

/**
 * Marker exception for invalid request input whose message is safe to return to API callers.
 */
public class SafeToReturnIllegalArgumentException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public SafeToReturnIllegalArgumentException(final String message) {
        super(message);
    }

    public SafeToReturnIllegalArgumentException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
