package portfolio.networkstackfromscratch.dns;

/** A DNS message or name that does not follow the wire format. */
public final class DnsFormatException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Exception with a description of the defect. */
    public DnsFormatException(String message) {
        super(message);
    }
}
