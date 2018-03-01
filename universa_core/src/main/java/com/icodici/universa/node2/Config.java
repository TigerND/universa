/*
 * Copyright (c) 2017 Sergey Chernov, iCodici S.n.C, All Rights Reserved
 *
 * Written by Sergey Chernov <real.sergeych@gmail.com>
 *
 */

package com.icodici.universa.node2;

import com.icodici.crypto.EncryptionError;
import com.icodici.crypto.PublicKey;
import com.icodici.universa.Approvable;
import com.icodici.universa.HashId;
import net.sergeych.utils.Bytes;

import java.time.Duration;
import java.time.temporal.TemporalAmount;
import java.util.ArrayList;
import java.util.List;

public class Config {



    public Config copy() {
        Config config = new Config();
        config.consensusConfigUpdater = consensusConfigUpdater;
        config.maxItemCreationAge = maxItemCreationAge;
        config.revokedItemExpiration = revokedItemExpiration;
        config.maxDownloadOnApproveTime = maxDownloadOnApproveTime;
        config.declinedItemExpiration = declinedItemExpiration;
        config.maxCacheAge = maxCacheAge;
        config.maxGetItemTime = maxGetItemTime;
        synchronized (this) {
            config.negativeConsensus = negativeConsensus;
            config.positiveConsensus = positiveConsensus;
            config.resyncBreakConsensus = resyncBreakConsensus;
        }
        config.maxElectionsTime = maxElectionsTime;
        config.pollTime = pollTime;
        config.consensusReceivedCheckTime = consensusReceivedCheckTime;
        config.maxConsensusReceivedCheckTime = maxConsensusReceivedCheckTime;
        config.resyncTime = resyncTime;
        config.checkItemTime = checkItemTime;
        config.maxResyncTime = maxResyncTime;
        config.transactionUnitsIssuerKeyData = transactionUnitsIssuerKeyData;
        config.networkConfigIssuerKeyData = networkConfigIssuerKeyData;
        return config;
    }

    private ConsensusConfigUpdater consensusConfigUpdater;

    public void setConsensusConfigUpdater(ConsensusConfigUpdater consensusConfigUpdater) {
        this.consensusConfigUpdater = consensusConfigUpdater;
    }


    public interface ConsensusConfigUpdater {
        void updateConsensusConfig(Config config, int nodesCount);
    }

    private Duration maxItemCreationAge = Duration.ofDays(5);
    private Duration revokedItemExpiration = maxItemCreationAge.plusDays(10);
    private TemporalAmount maxDownloadOnApproveTime = Duration.ofMinutes(5);

    public static <T> Class<T> forceInit(Class<T> klass) {
        try {
            Class.forName(klass.getName(), true, klass.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);  // Can't happen
        }
        return klass;
    }

    public Duration getDeclinedItemExpiration() {
        return declinedItemExpiration;
    }

    public void setDeclinedItemExpiration(Duration declinedItemExpiration) {
        this.declinedItemExpiration = declinedItemExpiration;
    }

    private Duration declinedItemExpiration = Duration.ofDays(10);
    private Duration maxCacheAge = Duration.ofMinutes(20);
    private Duration maxGetItemTime = Duration.ofSeconds(30);
    private int negativeConsensus;
    private int positiveConsensus;
    private int resyncBreakConsensus;
    private Duration maxElectionsTime = Duration.ofMinutes(15);
    private Duration pollTime = Duration.ofMillis(1000);
    private Duration consensusReceivedCheckTime = Duration.ofMillis(1000);
    private Duration maxConsensusReceivedCheckTime = Duration.ofMinutes(15);
    private Duration resyncTime = Duration.ofMillis(1000);
    private Duration checkItemTime = Duration.ofMillis(200);
    private Duration maxResyncTime = Duration.ofMinutes(5);
    private Bytes transactionUnitsIssuerKeyData = Bytes.fromHex("1E 08 1C 01 00 01 C4 00 01 B9 C7 CB 1B BA 3C 30 80 D0 8B 29 54 95 61 41 39 9E C6 BB 15 56 78 B8 72 DC 97 58 9F 83 8E A0 B7 98 9E BB A9 1D 45 A1 6F 27 2F 61 E0 26 78 D4 9D A9 C2 2F 29 CB B6 F7 9F 97 60 F3 03 ED 5C 58 27 27 63 3B D3 32 B5 82 6A FB 54 EA 26 14 E9 17 B6 4C 5D 60 F7 49 FB E3 2F 26 52 16 04 A6 5E 6E 78 D1 78 85 4D CD 7B 71 EB 2B FE 31 39 E9 E0 24 4F 58 3A 1D AE 1B DA 41 CA 8C 42 2B 19 35 4B 11 2E 45 02 AD AA A2 55 45 33 39 A9 FD D1 F3 1F FA FE 54 4C 2E EE F1 75 C9 B4 1A 27 5C E9 C0 42 4D 08 AD 3E A2 88 99 A3 A2 9F 70 9E 93 A3 DF 1C 75 E0 19 AB 1F E0 82 4D FF 24 DA 5D B4 22 A0 3C A7 79 61 41 FD B7 02 5C F9 74 6F 2C FE 9A DD 36 44 98 A2 37 67 15 28 E9 81 AC 40 CE EF 05 AA 9E 36 8F 56 DA 97 10 E4 10 6A 32 46 16 D0 3B 6F EF 80 41 F3 CC DA 14 74 D1 BF 63 AC 28 E0 F1 04 69 63 F7");
    private Bytes networkConfigIssuerKeyData = Bytes.fromHex("1E 08 1C 01 00 01 C4 00 01 83 D8 9D 79 7E 80 DD 69 3D 0A EC 27 66 B6 A4 5D DB E1 60 38 88 88 ED 07 03 E6 16 98 B0 2B 71 9B E1 85 A1 8C AF 0D 62 D6 60 3A 4B D2 FA 34 F0 2E 85 87 19 CE 6F 0C C6 DC 2B D5 11 12 C8 9A A6 F8 71 70 53 EE 3D B3 4C 97 1E 10 89 B1 77 2F 2B 6D D8 C7 B3 44 A4 8A E9 1A 42 AD F4 E0 82 74 11 A1 42 49 6C D1 87 35 94 10 66 19 80 AB 4A 13 27 B4 F0 BD C5 8F 43 25 9E 2C 6C CB 81 3C 85 10 CE 99 D6 2D 88 11 01 B6 5B F8 AB 99 15 70 08 AF B8 51 3B 4A CD 4D 9E A1 13 9C E9 EA 83 F0 95 02 E1 F6 10 72 E8 2B 2F 64 3F FB DC 27 F6 5A D2 83 BA 71 C3 D6 A2 AE 41 4F CA AA BB AA 54 C3 2F D9 F7 7A 64 AA 3A F7 67 AC 5A CA AA 20 08 90 CE D8 35 FA C0 2B 02 17 F4 0A BF 25 85 17 F9 DC 6E 6B 9D D8 A2 43 1E D1 0E CD 4F F4 FA 75 C1 62 BD 7B DD D4 2F 52 85 E0 FA 55 C7 B7 BB 4B 39 EB 08 74 C4 77");

    public static String tuTemplatePath = "./src/test_contracts/TUTemplate.yml";
    public static String tuKeyPath = "./src/test_contracts/keys/tu_key.private.unikey";

    /**
     * num of known (approved, declined, revoked or locked) subcontracts of a complex contract that starts resync if some another contracts is unknown
     */
    private int knownSubContractsToResync = 1;

    public int getPositiveConsensus() {
        return positiveConsensus;
    }

    public void setPositiveConsensus(int positiveConsensus) {
        this.positiveConsensus = positiveConsensus;
    }

    public int getResyncBreakConsensus() {
        return resyncBreakConsensus;
    }

    public void setResyncBreakConsensus(int resyncBreakConsensus) {
        this.resyncBreakConsensus = resyncBreakConsensus;
    }

    public Duration getMaxItemCreationAge() {
        return maxItemCreationAge;
    }

    public Duration getRevokedItemExpiration() {
        return revokedItemExpiration;
    }

    public Duration getMaxElectionsTime() {
        return maxElectionsTime;
    }

    public Duration getMaxConsensusReceivedCheckTime() {
        return maxConsensusReceivedCheckTime;
    }

    public Duration getMaxResyncTime() {
        return maxResyncTime;
    }

    public void setMaxResyncTime(Duration time) {
        maxResyncTime = time;
    }

    public void setMaxElectionsTime(Duration maxElectionsTime) {
        this.maxElectionsTime = maxElectionsTime;
    }

    public Duration getMaxCacheAge() {
        return maxCacheAge;
    }

    public void setMaxCacheAge(Duration maxCacheAge) {
        this.maxCacheAge = maxCacheAge;
    }

    public Duration getMaxGetItemTime() {
        return maxGetItemTime;
    }

    public void setMaxGetItemTime(Duration maxGetItemTime) {
        this.maxGetItemTime = maxGetItemTime;
    }

    public int getNegativeConsensus() {
        return negativeConsensus;
    }

    public void setNegativeConsensus(int negativeConsensus) {
        this.negativeConsensus = negativeConsensus;
    }

    public Duration getPollTime() {
        return pollTime;
    }

    public void setPollTime(Duration pollTime) {
        this.pollTime = pollTime;
    }

    public Duration getConsensusReceivedCheckTime() {
        return consensusReceivedCheckTime;
    }

    public void setConsensusReceivedCheckTime(Duration consensusReceivedCheckTime) {
        this.consensusReceivedCheckTime = consensusReceivedCheckTime;
    }

    public Duration getResyncTime() {
        return resyncTime;
    }

    public Duration getCheckItemTime() {
        return checkItemTime;
    }

    public void setResyncTime(Duration resyncTime) {
        this.resyncTime = resyncTime;
    }

    public TemporalAmount getMaxDownloadOnApproveTime() {
        return maxDownloadOnApproveTime;
    }

    public void setMaxDownloadOnApproveTime(TemporalAmount maxDownloadOnApproveTime) {
        this.maxDownloadOnApproveTime = maxDownloadOnApproveTime;
    }

    public int getResyncThreshold() {
        int n = getNegativeConsensus() * 2;
        if (n > getPositiveConsensus())
            n = getNegativeConsensus();
        return n;
    }

    /**
     * num of known (approved, declined, revoked or locked) subcontracts of a complex contract that starts resync
     * if some another contracts is unknown
     */
    public int getKnownSubContractsToResync() {
        return knownSubContractsToResync;
    }

    public PublicKey getTransactionUnitsIssuerKey() {
        try {
            return new PublicKey(transactionUnitsIssuerKeyData.getData());
        } catch (EncryptionError e) {
            return null;
        }
    }

    public PublicKey getNetworkConfigIssuerKey() {
        try {
            return new PublicKey(networkConfigIssuerKeyData.getData());
        } catch (EncryptionError e) {
            return null;
        }
    }

    public boolean updateConsensusConfig(int nodesCount) {
        synchronized (this) {
            if (consensusConfigUpdater != null) {
                consensusConfigUpdater.updateConsensusConfig(this, nodesCount);
                return true;
            }
            return false;
        }
    }
}
