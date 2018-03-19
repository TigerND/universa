/*
 * Copyright (c) 2018, All Rights Reserved
 *
 * Written by Leonid Novikov <flint.emerald@gmail.com>
 */

package com.icodici.universa.contract;

import com.icodici.crypto.AbstractKey;
import net.sergeych.biserializer.BiDeserializer;
import net.sergeych.biserializer.BiSerializable;
import net.sergeych.biserializer.BiSerializer;
import net.sergeych.biserializer.DefaultBiMapper;
import net.sergeych.tools.Binder;

import java.util.Arrays;
import java.util.Base64;

/**
 * Wrapper class for sequence of bytes, that was generated by {@link AbstractKey#createAnonymousId()}
 */
public class AnonymousId implements BiSerializable {

    private byte[] anonymousId = null;

    public AnonymousId() {}

    public AnonymousId(Binder binder) {
        anonymousId = Base64.getDecoder().decode(binder.getStringOrThrow("anonymousId"));
    }

    public AnonymousId(byte[] newValue) {
        this.anonymousId = newValue;
    }

    public static AnonymousId fromBytes(byte[] newValue) {
        return new AnonymousId(newValue);
    }

    public byte[] getBytes() {
        return this.anonymousId;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof AnonymousId) {
            AnonymousId k = (AnonymousId) obj;
            if ((anonymousId != null) && (k.anonymousId != null))
                return Arrays.equals(anonymousId, k.anonymousId);
        }
        return super.equals(obj);
    }

    @Override
    public int hashCode() {
        return anonymousId.hashCode();
    }

    @Override
    public void deserialize(Binder data, BiDeserializer deserializer) {
        anonymousId = data.getBinaryOrThrow("anonymousId");
    }

    @Override
    public Binder serialize(BiSerializer s) {
        return Binder.fromKeysValues(
                "anonymousId", anonymousId
        );
    }

    static {
        DefaultBiMapper.registerClass(AnonymousId.class);
    }

}
