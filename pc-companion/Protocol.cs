using System.Buffers.Binary;

namespace OpenGamepad.Companion;

/// <summary>
/// Wire format shared with the phone (compatible with Remote Gamepad).
/// Phone -> PC: u16 length | u8 major | u8 minor | u16 sequence | u8 type | payload
/// PC -> phone: u16 length | u8 major | u8 minor | u8 type | payload
/// All integers little-endian, except the 64-bit device id in the hello (big-endian).
/// </summary>
public static class Protocol
{
    public const byte Major = 1;
    public const byte Minor = 22;
    public const int RxHeaderSize = 7;
    public const int TxHeaderSize = 5;

    public const byte TypeHello = 0;
    public const byte TypeState = 1;
    public const byte TypeFeedback = 2;
    /// <summary>OpenGamepad extension: u8 HID modifiers, u8 count, count × u8 HID key usages.</summary>
    public const byte TypeKeyboard = 16;

    public const byte OsWindows = 1;

    /// <summary>Extension block appended to hello and reply when both sides are OpenGamepad.</summary>
    public static readonly byte[] ExtensionMagic = "OGP"u8.ToArray();
    public const byte ExtensionVersion = 1;

    public static byte[] Frame(byte type, ReadOnlySpan<byte> payload)
    {
        var packet = new byte[TxHeaderSize + payload.Length];
        BinaryPrimitives.WriteUInt16LittleEndian(packet, (ushort)packet.Length);
        packet[2] = Major;
        packet[3] = Minor;
        packet[4] = type;
        payload.CopyTo(packet.AsSpan(TxHeaderSize));
        return packet;
    }

    public static bool TryParse(ReadOnlySpan<byte> packet, out PhoneMessage message)
    {
        message = default;
        if (packet.Length < RxHeaderSize) return false;
        int length = BinaryPrimitives.ReadUInt16LittleEndian(packet);
        if (length < RxHeaderSize || length > packet.Length) return false;
        message = new PhoneMessage(
            packet[2], packet[3],
            BinaryPrimitives.ReadUInt16LittleEndian(packet[4..]),
            packet[6],
            packet[RxHeaderSize..length].ToArray());
        return true;
    }
}

public readonly record struct PhoneMessage(byte Major, byte Minor, ushort Sequence, byte Type, byte[] Payload);

/// <summary>Xbox 360 report: XUSB button bits, signed sticks, 0-255 triggers.</summary>
public readonly record struct ControllerState(
    ushort Buttons, short LeftX, short LeftY, short RightX, short RightY, byte LeftTrigger, byte RightTrigger)
{
    public static bool TryDecode(ReadOnlySpan<byte> p, out ControllerState state)
    {
        state = default;
        if (p.Length < 12) return false;
        state = new ControllerState(
            BinaryPrimitives.ReadUInt16LittleEndian(p),
            BinaryPrimitives.ReadInt16LittleEndian(p[2..]),
            BinaryPrimitives.ReadInt16LittleEndian(p[4..]),
            BinaryPrimitives.ReadInt16LittleEndian(p[6..]),
            BinaryPrimitives.ReadInt16LittleEndian(p[8..]),
            p[10], p[11]);
        return true;
    }
}
