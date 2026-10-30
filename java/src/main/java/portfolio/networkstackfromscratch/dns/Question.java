package portfolio.networkstackfromscratch.dns;

/** One entry of the question section. */
public record Question(String name, int type, int qclass) {
    /** Question in class IN with a normalized name. */
    public static Question of(String name, int type) {
        return new Question(DnsName.normalize(name), type, RecordType.CLASS_IN);
    }

    @Override
    public String toString() {
        return DnsName.display(name) + " " + RecordType.name(type);
    }
}
