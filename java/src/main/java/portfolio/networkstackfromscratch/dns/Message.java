package portfolio.networkstackfromscratch.dns;

import java.util.List;

/** A full DNS message: header plus the four sections. */
public record Message(Header header, List<Question> questions, List<ResourceRecord> answers,
                      List<ResourceRecord> authorities, List<ResourceRecord> additionals) {
    /** Payload size advertised with EDNS0 (DNS flag day 2020 recommendation). */
    public static final int EDNS_UDP_SIZE = 1232;

    /** Defensive copies. */
    public Message {
        questions = List.copyOf(questions);
        answers = List.copyOf(answers);
        authorities = List.copyOf(authorities);
        additionals = List.copyOf(additionals);
    }

    /** A query with one question, optionally with an EDNS0 OPT record. */
    public static Message query(int id, Question q, boolean recursionDesired, boolean edns) {
        List<ResourceRecord> extra = edns
                ? List.of(new ResourceRecord("", RecordType.OPT, EDNS_UDP_SIZE, 0, new RData.Opaque(new byte[0])))
                : List.of();
        return new Message(new Header(id, false, 0, false, false, recursionDesired, false, Header.NOERROR),
                List.of(q), List.of(), List.of(), extra);
    }

    /** The first question; throws when there is none. */
    public Question question() {
        if (questions.isEmpty()) {
            throw new DnsFormatException("message has no question");
        }
        return questions.get(0);
    }
}
