package portfolio.networkstackfromscratch.dns;

/** One resource record; {@code ttl} is in seconds. */
public record ResourceRecord(String name, int type, int rclass, long ttl, RData data) {
    /** Validate the TTL range (unsigned 32-bit on the wire). */
    public ResourceRecord {
        if (ttl < 0 || ttl > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("ttl out of range: " + ttl);
        }
    }

    /** Copy with another TTL (used when serving from the cache). */
    public ResourceRecord withTtl(long newTtl) {
        return new ResourceRecord(name, type, rclass, newTtl, data);
    }

    @Override
    public String toString() {
        return DnsName.display(name) + " " + ttl + " " + (rclass == RecordType.CLASS_IN ? "IN" : "CLASS" + rclass)
                + " " + RecordType.name(type) + " " + data;
    }
}
