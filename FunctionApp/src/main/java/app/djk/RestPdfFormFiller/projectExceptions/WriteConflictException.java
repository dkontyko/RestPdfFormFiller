package app.djk.RestPdfFormFiller.projectExceptions;

/**
 * Thrown when a fill request uses <code>writeMode=patch</code> with <code>patchMode=failOnConflict</code>
 * and a provided value would overwrite an existing, non-empty target with a different value.
 * <p>
 * The message contains only the structural field path (never the field values), so it is safe to
 * return to API callers.
 */
public class WriteConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public WriteConflictException(final String fieldPath) {
        super("Write conflict at field '" + fieldPath + "': target already has a different value.");
    }
}
