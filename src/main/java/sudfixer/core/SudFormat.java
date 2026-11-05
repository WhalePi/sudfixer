package sudfixer.core;

/**
 * Low level layout of a SoundTrap .sud file.
 *
 * <pre>
 * File header (30 bytes)
 *   0  HostCodeVersion   UInt16 LE
 *   2  HostTime          Int32  PDP
 *   6  DeviceType        byte
 *   7  DeviceCodeVersion byte
 *   8  DeviceTime        Int32  PDP
 *  12  DeviceIdentifier  Int32  PDP
 *  16  BlockLength       Int32  PDP
 *  20  StartBlock        UInt16 LE
 *  22  EndBlock          UInt16 LE
 *  24  NoOfBlocks        Int32  PDP
 *  28  Crc               UInt16 LE   (SUD CRC of bytes 0..27)
 *
 * Then a sequence of chunks, each a 20 byte header followed by DataLength bytes:
 *   0  magic        UInt16 LE  (0xA952)
 *   2  ChunkId      UInt16 LE  (0 = XML metadata)
 *   4  DataLength   UInt16 LE
 *   6  SampleCount  UInt16 LE
 *   8  TimeS        Int32  PDP (unix seconds, UTC)
 *  12  TimeOffsetUs Int32  PDP (microseconds within the second)
 *  16  DataCrc      UInt16 LE  (SUD CRC of the data)
 *  18  HeaderCrc    UInt16 LE  (SUD CRC of header bytes 0..17)
 * </pre>
 *
 * "PDP" is the middle-endian order SoundTraps use for 32 bit values: two little-endian
 * 16 bit words, most significant word first.
 */
public final class SudFormat {

    public static final int FILE_HEADER_BYTES = 30;
    public static final int CHUNK_HEADER_BYTES = 20;
    public static final int CHUNK_MAGIC = 0xA952;
    public static final int XML_CHUNK_ID = 0;

    private static final int[] CRC_TABLE = new int[256];

    static {
        // CCITT polynomial 0x1021, the table used by the SoundTrap firmware.
        for (int i = 0; i < 256; i++) {
            int crc = i << 8;
            for (int b = 0; b < 8; b++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
            }
            CRC_TABLE[i] = crc & 0xFFFF;
        }
    }

    private SudFormat() { }

    /**
     * The CRC used throughout SUD files. Works on 16 bit words stored little-endian,
     * high byte first, exactly as the SoundTrap C code does.
     */
    public static int crc(byte[] bytes, int offset, int length) {
        int fcs = 0xFFFF;
        int end = offset + (length & ~1);
        for (int i = offset; i < end; i += 2) {
            fcs = ((fcs << 8) ^ CRC_TABLE[((fcs >> 8) ^ bytes[i + 1]) & 0xFF]) & 0xFFFF;
            fcs = ((fcs << 8) ^ CRC_TABLE[((fcs >> 8) ^ bytes[i]) & 0xFF]) & 0xFFFF;
        }
        return fcs;
    }

    public static int getUInt16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    public static void putUInt16(byte[] b, int off, int value) {
        b[off] = (byte) value;
        b[off + 1] = (byte) (value >> 8);
    }

    /** A PDP-endian 32 bit value, returned unsigned. */
    public static long getUInt32Pdp(byte[] b, int off) {
        long v = ((long) (b[off + 1] & 0xFF) << 24) | ((b[off] & 0xFF) << 16)
                | ((b[off + 3] & 0xFF) << 8) | (b[off + 2] & 0xFF);
        return v & 0xFFFFFFFFL;
    }

    public static void putUInt32Pdp(byte[] b, int off, long value) {
        b[off] = (byte) (value >> 16);
        b[off + 1] = (byte) (value >> 24);
        b[off + 2] = (byte) value;
        b[off + 3] = (byte) (value >> 8);
    }

    /** Pairwise byte swap, as applied to XML and X3 chunk data. */
    public static void swapPairs(byte[] data) {
        for (int i = 0; i + 1 < data.length; i += 2) {
            byte t = data[i];
            data[i] = data[i + 1];
            data[i + 1] = t;
        }
    }
}
