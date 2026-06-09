package dev.redish.resp;

/**
 * Enum representing the first byte identifier of each RESP data type.
 */
public enum RespType {
    SIMPLE_STRING('+'),
    ERROR('-'),
    INTEGER(':'),
    BULK_STRING('$'),
    ARRAY('*');

    private final char identifier;

    RespType(char identifier) {
        this.identifier = identifier;
    }

    public char getIdentifier() {
        return identifier;
    }

    /**
     * Resolve a RespType from the leading byte.
     */
    public static RespType fromByte(byte b) {
        char c = (char) b;
        for (RespType type : values()) {
            if (type.identifier == c) {
                return type;
            }
        }
        throw new RespException("Unknown RESP type identifier: " + c);
    }
}
