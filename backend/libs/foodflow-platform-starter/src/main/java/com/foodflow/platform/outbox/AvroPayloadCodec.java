package com.foodflow.platform.outbox;

import com.foodflow.events.AvroClassTrust;
import org.apache.avro.Schema;
import org.apache.avro.message.BinaryMessageDecoder;
import org.apache.avro.message.BinaryMessageEncoder;
import org.apache.avro.specific.SpecificData;
import org.apache.avro.specific.SpecificRecord;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores outbox payloads as Avro single-object encoding (schema fingerprint + binary body).
 *
 * <p>Each row also stores the schema it was written with. When decoding, that writer schema is
 * registered with the decoder, so rows written by an older version of the service (before a
 * BACKWARD-compatible schema change) are resolved into the current generated class instead of
 * blocking the outbox during a rolling deployment.
 *
 * <p>Decoding is restricted to generated FoodFlow event classes, so a tampered row can never make
 * the relay load an arbitrary class.
 */
public class AvroPayloadCodec {

    private final Map<Class<?>, BinaryMessageEncoder<SpecificRecord>> encoders = new ConcurrentHashMap<>();
    private final Map<Class<?>, BinaryMessageDecoder<SpecificRecord>> decoders = new ConcurrentHashMap<>();
    private final Map<String, Schema> writerSchemas = new ConcurrentHashMap<>();

    public AvroPayloadCodec() {
        AvroClassTrust.install();
    }

    public byte[] encode(SpecificRecord record) {
        BinaryMessageEncoder<SpecificRecord> encoder = encoders.computeIfAbsent(record.getClass(),
                type -> new BinaryMessageEncoder<>(SpecificData.getForClass(type), record.getSchema()));
        try {
            ByteBuffer buffer = encoder.encode(record);
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return bytes;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot encode " + record.getClass().getName(), e);
        }
    }

    /**
     * @param payloadType   generated class name stored with the row
     * @param writerSchema  schema (JSON) the payload was written with; may be null for legacy rows
     */
    public SpecificRecord decode(String payloadType, String writerSchema, byte[] payload) {
        Class<?> type = resolve(payloadType);
        BinaryMessageDecoder<SpecificRecord> decoder = decoders.computeIfAbsent(type, t -> {
            SpecificData model = SpecificData.getForClass(t);
            return new BinaryMessageDecoder<>(model, model.getSchema(t));
        });
        if (writerSchema != null) {
            // addSchema is idempotent; parsing is cached per distinct schema text
            decoder.addSchema(writerSchemas.computeIfAbsent(writerSchema, json -> new Schema.Parser().parse(json)));
        }
        try {
            return decoder.decode(payload);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot decode outbox payload of type " + payloadType, e);
        }
    }

    private static Class<?> resolve(String payloadType) {
        if (payloadType == null || !payloadType.startsWith(AvroClassTrust.EVENTS_PACKAGE_PREFIX)) {
            throw new IllegalArgumentException("Untrusted outbox payload type: " + payloadType);
        }
        try {
            Class<?> type = Class.forName(payloadType, false, AvroPayloadCodec.class.getClassLoader());
            if (!SpecificRecord.class.isAssignableFrom(type)) {
                throw new IllegalArgumentException("Not an Avro SpecificRecord: " + payloadType);
            }
            return type;
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Unknown outbox payload type: " + payloadType, e);
        }
    }
}
