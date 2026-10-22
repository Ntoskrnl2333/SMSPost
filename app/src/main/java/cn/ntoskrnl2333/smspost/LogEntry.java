package cn.ntoskrnl2333.smspost;

/**
 * One forwarded (or attempted) SMS, as stored in the delivery log.
 */
public class LogEntry {
    public final long timestamp;
    public final String sender;
    public final String content;
    public final String status;

    public LogEntry(long timestamp, String sender, String content, String status) {
        this.timestamp = timestamp;
        this.sender = sender == null ? "" : sender;
        this.content = content == null ? "" : content;
        this.status = status == null ? "" : status;
    }
}
