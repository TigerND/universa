package com.icodici.universa.contract.services;

import com.icodici.crypto.EncryptionError;
import com.icodici.crypto.KeyAddress;
import com.icodici.crypto.PrivateKey;
import com.icodici.universa.Errors;
import com.icodici.universa.contract.Contract;
import com.icodici.universa.contract.Reference;
import com.icodici.universa.contract.TransactionPack;
import com.icodici.universa.contract.permissions.ModifyDataPermission;
import com.icodici.universa.contract.permissions.Permission;
import com.icodici.universa.contract.roles.RoleLink;
import com.icodici.universa.node.models.NameEntryModel;
import com.icodici.universa.node.models.NameRecordModel;
import com.icodici.universa.node2.Config;
import net.sergeych.biserializer.*;
import net.sergeych.boss.Boss;
import net.sergeych.tools.Binder;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.yaml.snakeyaml.Yaml;

import java.io.FileReader;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@BiType(name = "UnsContract")
public class UnsContract extends NSmartContract {
    public static final String NAMES_FIELD_NAME = "names";
    public static final String ENTRIES_FIELD_NAME = "entries";

    public static final String PREPAID_ND_FIELD_NAME = "prepaid_ND";
    public static final String PREPAID_ND_FROM_TIME_FIELD_NAME = "prepaid_ND_from";
    public static final String STORED_ENTRIES_FIELD_NAME = "stored_entries";
    public static final String SPENT_ND_FIELD_NAME = "spent_ND";
    public static final String SPENT_ND_TIME_FIELD_NAME = "spent_ND_time";

    private List<UnsName> storedNames = new ArrayList<>();
    private int paidU = 0;
    private double prepaidNamesForDays = 0;
    private long storedEarlyEntries = 0;
    private double spentEarlyNDs = 0;
    private double spentNDs = 0;
    private ZonedDateTime spentEarlyNDsTime = null;
    private ZonedDateTime prepaidFrom = null;
    private ZonedDateTime spentNDsTime = null;


    /**
     * Extract contract from v2 or v3 sealed form, getting revoking and new items from the transaction pack supplied. If
     * the transaction pack fails to resolve a link, no error will be reported - not sure it's a good idea. If need, the
     * exception could be generated with the transaction pack.
     * <p>
     * It is recommended to call {@link #check()} after construction to see the errors.
     *
     * @param sealed binary sealed contract.
     * @param pack   the transaction pack to resolve dependencies again.
     *
     * @throws IOException on the various format errors
     */
    public UnsContract(byte[] sealed, @NonNull TransactionPack pack) throws IOException {
        super(sealed, pack);

        deserializeForUns(BossBiMapper.newDeserializer());
    }

    public UnsContract() {
        super();
    }

    /**
     * Create a default empty new contract using a provided key as issuer and owner and sealer. Default expiration is
     * set to 5 years.
     * <p>
     * This constructor adds key as sealing signature so it is ready to {@link #seal()} just after construction, thought
     * it is necessary to put real data to it first. It is allowed to change owner, expiration and data fields after
     * creation (but before sealing).
     *
     * @param key is {@link PrivateKey} for creating roles "issuer", "owner", "creator" and sign contract
     */
    public UnsContract(PrivateKey key) {
        super(key);

        addUnsSpecific();
    }

    public void addUnsSpecific() {
        if(getDefinition().getExtendedType() == null || !getDefinition().getExtendedType().equals(SmartContractType.UNS1.name()))
            getDefinition().setExtendedType(SmartContractType.UNS1.name());

        // add modify_data permission

        boolean permExist = false;
        Collection<Permission> mdps = getPermissions().get(ModifyDataPermission.FIELD_NAME);
        if(mdps != null) {
            for (Permission perm : mdps) {
                if (perm.getName() == ModifyDataPermission.FIELD_NAME) {
                    if (perm.isAllowedForKeys(getOwner().getKeys())) {
                        permExist = true;
                        break;
                    }
                }
            }
        }

        if(!permExist) {
            RoleLink ownerLink = new RoleLink("owner_link", "owner");
            registerRole(ownerLink);
            HashMap<String, Object> fieldsMap = new HashMap<>();
            fieldsMap.put("action", null);
            fieldsMap.put("/expires_at", null);
            fieldsMap.put(NAMES_FIELD_NAME, null);
            fieldsMap.put(PREPAID_ND_FIELD_NAME, null);
            fieldsMap.put(PREPAID_ND_FROM_TIME_FIELD_NAME, null);
            fieldsMap.put(STORED_ENTRIES_FIELD_NAME, null);
            fieldsMap.put(SPENT_ND_FIELD_NAME, null);
            fieldsMap.put(SPENT_ND_TIME_FIELD_NAME, null);
            Binder modifyDataParams = Binder.of("fields", fieldsMap);
            ModifyDataPermission modifyDataPermission = new ModifyDataPermission(ownerLink, modifyDataParams);
            addPermission(modifyDataPermission);
        }
    }

    public double getPrepaidNamesForDays() {
        return prepaidNamesForDays;
    }

    private double calculatePrepaidNamesForDays(boolean withSaveToState) {

        for (Contract nc : getNew()) {
            if (nc.isU(nodeConfig.getTransactionUnitsIssuerKeys(), nodeConfig.getTUIssuerName())) {
                int calculatedPayment = 0;
                boolean isTestPayment = false;
                Contract parent = null;
                for (Contract nrc : nc.getRevoking()) {
                    if (nrc.getId().equals(nc.getParent())) {
                        parent = nrc;
                        break;
                    }
                }
                if (parent != null) {
                    boolean hasTestTU = nc.getStateData().get("test_transaction_units") != null;
                    if (hasTestTU) {
                        isTestPayment = true;
                        calculatedPayment = parent.getStateData().getIntOrThrow("test_transaction_units")
                                - nc.getStateData().getIntOrThrow("test_transaction_units");

                        if (calculatedPayment <= 0) {
                            isTestPayment = false;
                            calculatedPayment = parent.getStateData().getIntOrThrow("transaction_units")
                                    - nc.getStateData().getIntOrThrow("transaction_units");
                        }
                    } else {
                        isTestPayment = false;
                        calculatedPayment = parent.getStateData().getIntOrThrow("transaction_units")
                                - nc.getStateData().getIntOrThrow("transaction_units");
                    }
                }

                if(!isTestPayment) {
                    paidU = calculatedPayment;
                }
            }
        }

        ZonedDateTime now = ZonedDateTime.ofInstant(Instant.ofEpochSecond(ZonedDateTime.now().toEpochSecond()), ZoneId.systemDefault());
        double wasPrepaidNamesForDays;
        long wasPrepaidFrom = now.toEpochSecond();
        long spentEarlyNDsTimeSecs = now.toEpochSecond();
        Contract parentContract = getRevokingItem(getParent());
        if(parentContract != null) {
            wasPrepaidNamesForDays = parentContract.getStateData().getDouble(PREPAID_ND_FIELD_NAME);
            wasPrepaidFrom = parentContract.getStateData().getLong(PREPAID_ND_FROM_TIME_FIELD_NAME, now.toEpochSecond());
            storedEarlyEntries = parentContract.getStateData().getLong(STORED_ENTRIES_FIELD_NAME, 0);
            spentEarlyNDs = parentContract.getStateData().getDouble(SPENT_ND_FIELD_NAME);
            spentEarlyNDsTimeSecs = parentContract.getStateData().getLong(SPENT_ND_TIME_FIELD_NAME, now.toEpochSecond());
        } else {
            wasPrepaidNamesForDays = 0;
        }

        spentEarlyNDsTime = ZonedDateTime.ofInstant(Instant.ofEpochSecond(spentEarlyNDsTimeSecs), ZoneId.systemDefault());
        prepaidFrom = ZonedDateTime.ofInstant(Instant.ofEpochSecond(wasPrepaidFrom), ZoneId.systemDefault());
        prepaidNamesForDays = wasPrepaidNamesForDays + paidU * Config.namesAndDaysPerU;

        spentNDsTime = now;

        if(withSaveToState) {
            getStateData().set(PREPAID_ND_FIELD_NAME, prepaidNamesForDays);
            if(getRevision() == 1)
                getStateData().set(PREPAID_ND_FROM_TIME_FIELD_NAME, now.toEpochSecond());

            // calculate num of entries
            int storingEntries = 0;
            for (Object name: storedNames) {
                if (name.getClass().getName().endsWith("UnsName"))
                    storingEntries += ((UnsName) name).getRecordsCount();
                else {
                    Binder binder;
                    if (name.getClass().getName().endsWith("Binder"))
                        binder = (Binder) name;
                    else
                        binder = new Binder((Map) name);

                    ArrayList<?> entries = binder.getArray(ENTRIES_FIELD_NAME);
                    if (entries != null)
                        storingEntries += entries.size();
                }
            }
            getStateData().set(STORED_ENTRIES_FIELD_NAME, storingEntries);

            long spentSeconds = (spentNDsTime.toEpochSecond() - spentEarlyNDsTime.toEpochSecond());
            double spentDays = (double) spentSeconds / (3600 * 24);
            spentNDs = spentEarlyNDs + spentDays * (storedEarlyEntries / 1024);

            getStateData().set(SPENT_ND_FIELD_NAME, spentNDs);
            getStateData().set(SPENT_ND_TIME_FIELD_NAME, spentNDsTime.toEpochSecond());
        }

        return prepaidNamesForDays;
    }

    @Override
    public byte[] seal() {
        calculatePrepaidNamesForDays(true);

        return super.seal();
    }

    @Override
    public void deserialize(Binder data, BiDeserializer deserializer) {
        super.deserialize(data, deserializer);

        deserializeForUns(deserializer);
    }

    // this method should be only at the deserialize
    private void deserializeForUns(BiDeserializer deserializer) {

        storedNames = deserializer.deserialize(getStateData().getList(NAMES_FIELD_NAME, null));

        prepaidNamesForDays = getStateData().getInt(PREPAID_ND_FIELD_NAME, 0);

        long prepaidFromSeconds = getStateData().getLong(PREPAID_ND_FROM_TIME_FIELD_NAME, 0);
        prepaidFrom = ZonedDateTime.ofInstant(Instant.ofEpochSecond(prepaidFromSeconds), ZoneId.systemDefault());
    }

    protected UnsContract initializeWithDsl(Binder root) throws EncryptionError {
        super.initializeWithDsl(root);
        ArrayList<?> arrayNames = root.getBinder("state").getBinder("data").getArray(NAMES_FIELD_NAME);
        for (Object name: arrayNames) {
            Binder binder;
            if (name.getClass().getName().endsWith("Binder"))
                binder = (Binder) name;
            else
                binder = new Binder((Map) name);

            UnsName unsName = new UnsName().initializeWithDsl(binder);
            storedNames.add(unsName);
        }
        return this;
    }

    public static UnsContract fromDslFile(String fileName) throws IOException {
        Yaml yaml = new Yaml();
        try (FileReader r = new FileReader(fileName)) {
            Binder binder = Binder.from(DefaultBiMapper.deserialize((Map) yaml.load(r)));
            return new UnsContract().initializeWithDsl(binder);
        }
    }

    @Override
    public boolean beforeCreate(ImmutableEnvironment c) {

        boolean checkResult = false;

        calculatePrepaidNamesForDays(false);

        boolean hasPayment = false;
        for (Contract nc : getNew()) {
            if (nc.isU(nodeConfig.getTransactionUnitsIssuerKeys(), nodeConfig.getTUIssuerName())) {
                hasPayment = true;

                int calculatedPayment = 0;
                boolean isTestPayment = false;
                Contract parent = null;
                for (Contract nrc : nc.getRevoking()) {
                    if (nrc.getId().equals(nc.getParent())) {
                        parent = nrc;
                        break;
                    }
                }
                if (parent != null) {
                    boolean hasTestTU = nc.getStateData().get("test_transaction_units") != null;
                    if (hasTestTU) {
                        isTestPayment = true;
                        if (calculatedPayment <= 0)
                            isTestPayment = false;
                    } else
                        isTestPayment = false;

                    if (isTestPayment) {
                        hasPayment = false;
                        addError(Errors.FAILED_CHECK, "Test payment is not allowed for storing names");
                    }

                    if (paidU < nodeConfig.getMinUnsPayment()) {
                        hasPayment = false;
                        addError(Errors.FAILED_CHECK, "Payment for UNS contract is below minimum level of " + nodeConfig.getMinUnsPayment() + "U");
                    }
                } else {
                    hasPayment = false;
                    addError(Errors.FAILED_CHECK, "Payment contract is missing parent contract");
                }
            }
        }

        checkResult = hasPayment;
        if (!checkResult) {
            addError(Errors.FAILED_CHECK, "UNS contract hasn't valid payment");
            return checkResult;
        }

        checkResult = prepaidNamesForDays == getStateData().getInt(PREPAID_ND_FIELD_NAME, 0);
        if (!checkResult) {
            addError(Errors.FAILED_CHECK, "Wrong [state.data." + PREPAID_ND_FIELD_NAME + "] value. " +
                    "Should be sum of early paid U and paid U by current revision.");
            return checkResult;
        }

        checkResult = additionallyUnsCheck(c);

        return checkResult;
    }

    @Override
    public boolean beforeUpdate(ImmutableEnvironment c) {
        boolean checkResult = false;

        calculatePrepaidNamesForDays(false);

        checkResult = prepaidNamesForDays == getStateData().getInt(PREPAID_ND_FIELD_NAME, 0);
        if (!checkResult) {
            addError(Errors.FAILED_CHECK, "Wrong [state.data." + PREPAID_ND_FIELD_NAME + "] value. " +
                    "Should be sum of early paid U and paid U by current revision.");
            return checkResult;
        }

        checkResult = additionallyUnsCheck(c);

        return checkResult;
    }

    @Override
    public boolean beforeRevoke(ImmutableEnvironment c) {
        return additionallyUnsCheck(c);
    }

    private boolean additionallyUnsCheck(ImmutableEnvironment ime) {

        boolean checkResult = false;

        checkResult = ime != null;
        if (!checkResult) {
            addError(Errors.FAILED_CHECK, "Environment should be not null");
            return checkResult;
        }

        checkResult = getExtendedType().equals(SmartContractType.UNS1.name());
        if (!checkResult) {
            addError(Errors.FAILED_CHECK, "definition.extended_type", "illegal value, should be " + SmartContractType.UNS1.name() + " instead " + getExtendedType());
            return checkResult;
        }


        checkResult = (storedNames.size() > 0);
        if (!checkResult) {
            addError(Errors.FAILED_CHECK, NAMES_FIELD_NAME,"Names for storing is missing");
            return checkResult;
        }

        checkResult = storedNames.stream().allMatch(n -> n.getUnsRecords().stream().allMatch(unsRecord -> {
            if(unsRecord.getOrigin() != null) {
                if(!unsRecord.getAddresses().isEmpty()) {
                    addError(Errors.FAILED_CHECK, NAMES_FIELD_NAME, "name " + n.getUnsName() + " referencing to origin " + unsRecord.getOrigin().toString() + " AND addresses. Should be either origin or addresses");
                    return false;
                }

                List<Reference> matchingRefs = getReferences().values().stream().filter(ref ->
                        ref.getContract().getId().equals(unsRecord.getOrigin())
                                || ref.getContract().getOrigin() != null && ref.getContract().getOrigin().equals(unsRecord.getOrigin())).collect(Collectors.toList());
                if(matchingRefs.isEmpty()) {
                    addError(Errors.FAILED_CHECK, NAMES_FIELD_NAME, "name " + n.getUnsName() + " referencing to origin " + unsRecord.getOrigin().toString() + " but no corresponding reference is found");
                    return false;
                }

                Contract contract = matchingRefs.get(0).getContract();
                if(!contract.getRole("issuer").isAllowedForKeys(getSealedByKeys())) {
                    addError(Errors.FAILED_CHECK, NAMES_FIELD_NAME, "name " + n.getUnsName() + " referencing to origin " + unsRecord.getOrigin().toString() + ". UNS1 contract should be also signed by this contract issuer key.");
                    return false;
                }

                return true;
            }

            if(unsRecord.getAddresses().isEmpty()) {
                addError(Errors.FAILED_CHECK, NAMES_FIELD_NAME, "name " + n.getUnsName() + " is missing both addresses and origin.");
                return false;
            }

            KeyAddress longAddress = null;
            KeyAddress shortAddress = null;
            for(KeyAddress address : unsRecord.getAddresses()) {
                if (address.isLong()) {
                    if (longAddress != null) {
                        addError(Errors.FAILED_CHECK, NAMES_FIELD_NAME, "name " + n.getUnsName() + " should refer to 1 LONG and 1 short address at maximum.");
                        return false;
                    } else {
                        longAddress = address;
                    }
                } else {
                    if (shortAddress != null) {
                        addError(Errors.FAILED_CHECK, NAMES_FIELD_NAME, "name " + n.getUnsName() + " should refer to 1 long and 1 SHORT address at maximum.");
                        return false;
                    } else {
                        shortAddress = address;
                    }
                }
            }
            if(!shortAddress.isMatchingKeyAddress(longAddress)) {
                addError(Errors.FAILED_CHECK, NAMES_FIELD_NAME, "name " + n.getUnsName() + " should refer to 1 LONG and 1 short address at maximum.");
                return false;
            }

            //TODO: check only one address as they should match
            if(!unsRecord.getAddresses().stream().allMatch(keyAddress -> getSealedByKeys().stream().anyMatch(key -> keyAddress.isMatchingKey(key)))) {
                addError(Errors.FAILED_CHECK, NAMES_FIELD_NAME, "name " + n.getUnsName() + " using address that missing corresponding key UNS contract signed with.");
                return false;
            }

            return true;
        }));

        if (!checkResult) {
            return checkResult;
        }

        checkResult = getSealedByKeys().contains(nodeConfig.getAuthorizedNameServiceCenterKey());
        if(!checkResult) {
            addError(Errors.FAILED_CHECK, NAMES_FIELD_NAME,"Authorized name service signature is missing");
            return checkResult;
        }

        //TODO: tryAllocateName


        return checkResult;
    }

    @Override
    public @Nullable Binder onCreated(MutableEnvironment me) {
        long environmentId = ledger.saveEnvironmentToStorage(getExtendedType(), getId(), Boss.pack(me), getPackedTransaction());

        storedNames.forEach(sn -> {
                    NameRecordModel nrm = new NameRecordModel();
                    nrm.name_full = sn.getUnsName();
                    nrm.name_reduced = sn.getUnsNameReduced();
                    nrm.description = sn.getUnsDescription();
                    nrm.url = sn.getUnsURL();
                    nrm.expires_at = spentNDsTime.plusSeconds((long) (prepaidNamesForDays * 24 * 3600));
                    sn.getUnsRecords().forEach(snr ->{
                        NameEntryModel nem = new NameEntryModel();
                        if(snr.getOrigin() != null) {
                            nem.origin = snr.getOrigin().getDigest();
                        }
                        snr.getAddresses().forEach(keyAddress -> {
                            if(keyAddress.isLong()) {
                                nem.long_addr = keyAddress.toString();
                            } else {
                                nem.short_addr = keyAddress.toString();
                            }
                        });
                        nrm.entries.add(nem);
                    });
                    nrm.environment_id = environmentId;
                    ledger.saveNameRecord(nrm);
                }
        );

        return Binder.fromKeysValues("status", "ok");
    }

    @Override
    public Binder onUpdated(MutableEnvironment me) {
        ledger.removeEnvironment(getId());

        long environmentId = ledger.saveEnvironmentToStorage(getExtendedType(), getId(), Boss.pack(me), getPackedTransaction());

        storedNames.forEach(sn -> {
                    NameRecordModel nrm = new NameRecordModel();
                    nrm.name_full = sn.getUnsName();
                    nrm.name_reduced = sn.getUnsNameReduced();
                    nrm.description = sn.getUnsDescription();
                    nrm.url = sn.getUnsURL();
                    nrm.expires_at = spentNDsTime.plusSeconds((long) (prepaidNamesForDays * 24 * 3600));
                    sn.getUnsRecords().forEach(snr ->{
                        NameEntryModel nem = new NameEntryModel();
                        if(snr.getOrigin() != null) {
                            nem.origin = snr.getOrigin().getDigest();
                        }
                        snr.getAddresses().forEach(keyAddress -> {
                            if(keyAddress.isLong()) {
                                nem.long_addr = keyAddress.toString();
                            } else {
                                nem.short_addr = keyAddress.toString();
                            }
                        });
                        nrm.entries.add(nem);
                    });
                    nrm.environment_id = environmentId;
                    ledger.saveNameRecord(nrm);
                }
        );

        return Binder.fromKeysValues("status", "ok");
    }

    @Override
    public void onRevoked(ImmutableEnvironment ime) {
        ledger.removeEnvironment(getId());

    }

    public void addUnsName(UnsName unsName, Collection<Contract> referencedOrigins) {
        unsName.getUnsRecords().forEach(unsRecord -> {
            if(unsRecord.getOrigin() != null) {
                if(referencedOrigins != null) {
                    List<Contract> matchingRefs = referencedOrigins.stream().filter(contract ->
                            contract.getId().equals(unsRecord.getOrigin()) ||
                                    contract.getOrigin() != null && contract.getOrigin().equals(unsRecord.getOrigin())
                    ).collect(Collectors.toList());
                    if(!matchingRefs.isEmpty()) {
                        addReference(new Reference(matchingRefs.get(0)));
                    }
                }
            }
        });
        storedNames.add(unsName);
    }


    public void addUnsName(UnsName unsName) {
        addUnsName(unsName,null);
    }

    static {
        Config.forceInit(UnsRecord.class);
        Config.forceInit(UnsName.class);
        Config.forceInit(UnsContract.class);
        DefaultBiMapper.registerClass(UnsRecord.class);
        DefaultBiMapper.registerClass(UnsName.class);
        DefaultBiMapper.registerClass(UnsContract.class);
    }
}

