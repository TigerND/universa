/*
 * Copyright (c) 2017 Sergey Chernov, iCodici S.n.C, All Rights Reserved
 *
 * Written by Sergey Chernov <real.sergeych@gmail.com>
 *
 */

package com.icodici.universa.node2;

import com.icodici.crypto.PrivateKey;
import com.icodici.universa.Approvable;
import com.icodici.universa.Decimal;
import com.icodici.universa.HashId;
import com.icodici.universa.contract.Contract;
import com.icodici.universa.node.*;
import com.icodici.universa.node.network.TestKeys;
import com.icodici.universa.node2.network.DatagramAdapter;
import com.icodici.universa.node2.network.Network;
import net.sergeych.tools.AsyncEvent;
import net.sergeych.utils.LogPrinter;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.junit.*;

import java.io.File;
import java.io.FileReader;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.TimeoutException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.number.OrderingComparison.lessThan;
import static org.junit.Assert.*;

public class Node2EmulatedNetworkTest extends TestCase {

    protected static final String ROOT_PATH = "./src/test_contracts/";
    protected static final String CONFIG_2_PATH = "./src/test_config_2/";

    public static int NODES = 10;

    public static List<Node> nodes = new ArrayList<>();

    private static TestEmulatedNetwork network;
    private static NetConfig nc;
    private static Config config;
    private static Node node;
    private static NodeInfo myInfo;
    private static Ledger ledger;

    @BeforeClass
    public static void setUp() throws Exception {

        System.out.println("Emulated network setup");
        nodes = new ArrayList<>();
        config = new Config();
        config.setPositiveConsensus(7);
        config.setNegativeConsensus(4);
        config.setResyncBreakConsensus(2);

        Properties properties = new Properties();
        File file = new File(CONFIG_2_PATH + "config/config.yaml");
        if (file.exists())
            properties.load(new FileReader(file));

        nc = new NetConfig();
        TestEmulatedNetwork en = new TestEmulatedNetwork(nc);

        for (int i = 0; i < NODES; i++) {

            ledger = new PostgresLedger(PostgresLedgerTest.CONNECTION_STRING + "_t" + i, properties);

            int offset = 7100 + 10 * i;
            NodeInfo info =
                    new NodeInfo(
                            getNodeKey(i).getPublicKey(),
                            i,
                            "testnode_" + i,
                            "localhost",
                            offset + 3,
                            offset,
                            offset + 2
                    );
            nc.addNode(info);
            Node n = new Node(config, info, ledger, en);
            nodes.add(n);
            en.addNode(info, n);
        }
        network = en;
        node = nodes.get(0);
        System.out.println("Emulated network created on the nodes: " + nodes);
        System.out.println("Emulated network base node is: " + node);
        Thread.sleep(100);
    }

    @Before
    public void setUpTest() throws Exception {
        System.out.println("setup test");
        System.out.println("Switch on  network full mode");
        network.switchOnAllNodesTestMode();
        network.setTest_nodeBeingOffedChance(0);
    }

    @After
    public void tearDownTest() throws Exception {
        System.out.println("tear down test");
    }

    @Test(timeout = 20000)
    public void registerGoodItem() throws Exception {
        int N = 100;
        for (int k = 0; k < 1; k++) {
//            StopWatch.measure(true, () -> {
            for (int i = 0; i < N; i++) {
                TestItem ok = new TestItem(true);
                System.out.println("\n--------------register item " + ok.getId() + " ------------\n");
                node.registerItem(ok);
                for (Node n : nodes) {
                    try {
                        ItemResult r = n.waitItem(ok.getId(), 2500);
                        while( !r.state.isConsensusFound()) {
                            System.out.println("wait for consensus receiving on the node " + n);
                            Thread.sleep(200);
                            r = n.waitItem(ok.getId(), 2500);
                        }
                        System.out.println("In node " + n + " item " + ok.getId() + " has state " +  r.state);
                        assertEquals(ItemState.APPROVED, r.state);
                    } catch (TimeoutException e) {
                        fail("timeout");
                    }
                }
            }
//            });
//            assertThat(node.countElections(), is(lessThan(10)));
        }
    }

    //    @Test
    public void unexpectedStrangeCaseWithConcurrent() throws Exception {
        String FIELD_NAME = "amount";
        PrivateKey ownerKey2 = TestKeys.privateKey(1);
        String PRIVATE_KEY = "_xer0yfe2nn1xthc.private.unikey";

        Contract root = Contract.fromDslFile("./src/test_contracts/coin.yml");
        root.getStateData().set(FIELD_NAME, new Decimal(200));
        root.addSignerKeyFromFile("./src/test_contracts/" + PRIVATE_KEY);
        root.setOwnerKey(ownerKey2);
        root.seal();
        assertTrue(root.check());


        Contract c1 = root.splitValue(FIELD_NAME, new Decimal(100));
        c1.seal();
        assertTrue(root.check());
        assertTrue(c1.isOk());

        // c1 split 50 50
        c1 = c1.createRevision(ownerKey2);
        c1.seal();
        Contract c50_1 = c1.splitValue(FIELD_NAME, new Decimal(50));
        c50_1.seal();
        assertTrue(c50_1.isOk());

        //good join
        Contract finalC = c50_1.createRevision(ownerKey2);
        finalC.seal();

        finalC.getStateData().set(FIELD_NAME, new Decimal(100));
        finalC.addRevokingItems(c50_1);
        finalC.addRevokingItems(c1);

        for (int j = 0; j < 500; j++) {

            HashId id;
            StateRecord orCreate;

            int p = 0;
            for (Approvable c : finalC.getRevokingItems()) {
                id = c.getId();
                for (int i = 0; i < nodes.size(); i++) {
                    if (i == nodes.size() - 1 && p == 1) break;

                    Node nodeS = nodes.get(i);
                    orCreate = nodeS.getLedger().findOrCreate(id);
                    orCreate.setState(ItemState.APPROVED).save();
                }
                ++p;
            }

            destroyFromAllNodesExistingNew(finalC);

            destroyCurrentFromAllNodesIfExists(finalC);

            node.registerItem(finalC);
            ItemResult itemResult = node.waitItem(finalC.getId(), 1500);
            System.out.println(itemResult.state);
//            if (ItemState.APPROVED != itemResult.state)
//                System.out.println("\r\nWrong state on repetition " + j + ": " + itemResult + ", " + itemResult.errors +
//                        " \r\ncontract_errors: " + finalC.getErrors());
//            else
//                System.out.println("\r\nGood. repetition: " + j + " ledger:" + node.getLedger().toString());
//                fail("Wrong state on repetition " + j + ": " + itemResult + ", " + itemResult.errors +
//                        " \r\ncontract_errors: " + finalC.getErrors());

//            assertEquals(ItemState.APPROVED, itemResult.state);
        }

    }

    private void destroyCurrentFromAllNodesIfExists(Contract finalC) {
        for (Node nodeS : nodes) {
            StateRecord r = nodeS.getLedger().getRecord(finalC.getId());
            if (r != null) {
                r.destroy();
            }
        }
    }

    @Test
    public void checkSimpleCase() throws Exception {
//        String transactionName = "./src/test_contracts/transaction/93441e20-242a-4e91-b283-8d0fd5f624dd.transaction";

        for (int i = 0; i < 5; i++) {
            Contract contract = Contract.fromDslFile(ROOT_PATH + "coin100.yml");
            contract.addSignerKeyFromFile(ROOT_PATH +"_xer0yfe2nn1xthc.private.unikey");
            contract.seal();

            addDetailsToAllLedgers(contract);

            contract.check();
            contract.traceErrors();
            assertTrue(contract.isOk());

            node.registerItem(contract);
            ItemResult itemResult = node.waitItem(contract.getId(), 1500);
            if (ItemState.APPROVED != itemResult.state)
                fail("Wrong state on repetition " + i + ": " + itemResult + ", " + itemResult.errors +
                        " \r\ncontract_errors: " + contract.getErrors());

            assertEquals(ItemState.APPROVED, itemResult.state);
        }
    }

    @Test(timeout = 5000)
    public void resyncApproved() throws Exception {
        Contract c = new Contract(TestKeys.privateKey(0));
        c.seal();
        addToAllLedgers(c, ItemState.APPROVED);

        node.getLedger().getRecord(c.getId()).destroy();
        assertEquals(ItemState.UNDEFINED, node.checkItem(c.getId()).state);

//        LogPrinter.showDebug(true);
        node.resync(c.getId());

        assertEquals(ItemState.APPROVED, node.waitItem(c.getId(), 5000).state);
    }

    @Test(timeout = 10000)
    public void resyncRevoked() throws Exception {
        Contract c = new Contract(TestKeys.privateKey(0));
        c.seal();
        addToAllLedgers(c, ItemState.REVOKED);

        node.getLedger().getRecord(c.getId()).destroy();
        assertEquals(ItemState.UNDEFINED, node.waitItem(c.getId(), 2000).state);

//        LogPrinter.showDebug(true);
        node.resync(c.getId());

        assertEquals(ItemState.REVOKED, node.waitItem(c.getId(), 2000).state);
    }

    @Test
    public void resyncDeclined() throws Exception {
        Contract c = new Contract(TestKeys.privateKey(0));
        c.seal();
        addToAllLedgers(c, ItemState.DECLINED);

        node.getLedger().getRecord(c.getId()).destroy();
        assertEquals(ItemState.UNDEFINED, node.checkItem(c.getId()).state);

//        LogPrinter.showDebug(true);
        node.resync(c.getId());

        assertEquals(ItemState.DECLINED, node.waitItem(c.getId(), 2000).state);
    }

    @Test
    public void resyncOther() throws Exception {

//        LogPrinter.showDebug(true);
        Contract c = new Contract(TestKeys.privateKey(0));
        c.seal();
        addToAllLedgers(c, ItemState.PENDING_POSITIVE);

        node.getLedger().getRecord(c.getId()).destroy();
        assertEquals(ItemState.UNDEFINED, node.checkItem(c.getId()).state);

        node.resync(c.getId());
        assertEquals(ItemState.PENDING, node.checkItem(c.getId()).state);

        assertEquals(ItemState.UNDEFINED, node.waitItem(c.getId(), 2000).state);
    }

    @Test(timeout = 5000)
    public void resyncWithTimeout() throws Exception {

//        LogPrinter.showDebug(true);

        Contract c = new Contract(TestKeys.privateKey(0));
        c.seal();
        addToAllLedgers(c, ItemState.APPROVED);

        Duration wasDuration = config.getMaxResyncTime();
        config.setMaxResyncTime(Duration.ofMillis(2000));

        for (int i = 0; i < NODES/2; i++) {
            network.switchOffNodeTestMode(nodes.get(NODES-i-1));
        }

        node.getLedger().getRecord(c.getId()).destroy();
        assertEquals(ItemState.UNDEFINED, node.checkItem(c.getId()).state);

        node.resync(c.getId());
        assertEquals(ItemState.PENDING, node.checkItem(c.getId()).state);

        assertEquals(ItemState.UNDEFINED, node.waitItem(c.getId(), 5000).state);

        config.setMaxResyncTime(wasDuration);

        network.switchOnAllNodesTestMode();
    }

    @Test
    public void resyncComplex() throws Exception {

//        LogPrinter.showDebug(true);

        int numSubContracts = 5;
        List<Contract> subContracts = new ArrayList<>();
        for (int i = 0; i < numSubContracts; i++) {
            Contract c = Contract.fromDslFile(ROOT_PATH + "coin100.yml");
            c.addSignerKeyFromFile(ROOT_PATH +"_xer0yfe2nn1xthc.private.unikey");
            assertTrue(c.check());
            c.seal();

            if(i < config.getKnownSubContractsToResync())
                addToAllLedgers(c, ItemState.APPROVED);
            else
                addToAllLedgers(c, ItemState.APPROVED, node);

            subContracts.add(c);
        }

        for (int i = 0; i < numSubContracts; i++) {
            ItemResult r = node.checkItem(subContracts.get(i).getId());
            System.out.println("Contract: " + i + " state: " + r.state);
        }

        Contract contract = new Contract(TestKeys.privateKey(0));
        contract.seal();

        for (int i = 0; i < numSubContracts; i++) {
            contract.addRevokingItems(subContracts.get(i));
        }
        addToAllLedgers(contract, ItemState.PENDING_POSITIVE);

        node.getLedger().getRecord(contract.getId()).destroy();
        assertEquals(ItemState.UNDEFINED, node.checkItem(contract.getId()).state);

        node.resync(contract.getId());
        assertEquals(ItemState.PENDING, node.checkItem(contract.getId()).state);

        assertEquals(ItemState.UNDEFINED, node.waitItem(contract.getId(), 2000).state);
    }

    private void addToAllLedgers(Contract c, ItemState state) {
        addToAllLedgers(c, state, null);
    }

    private void addToAllLedgers(Contract c, ItemState state, Node exceptNode) {
        for( Node n: nodes ) {
            if(n != exceptNode) {
                n.getLedger().findOrCreate(c.getId()).setState(state).save();
            }
        }
    }

    //    @Test
//    public void checkSergeychCase() throws Exception {
//        String transactionName = "./src/test_contracts/transaction/b8f8a512-8c45-4744-be4e-d6788729b2a7.transaction";
//
//        for (int i = 0; i < 5; i++) {
//            Contract contract = readContract(transactionName, true);
//
//            addDetailsToAllLedgers(contract);
//
//            contract.check();
//            contract.traceErrors();
//            assertTrue(contract.isOk());
//
//            node.registerItem(contract);
//            ItemResult itemResult = node.waitItem(contract.getId(), 15000);
//
//            if (ItemState.APPROVED != itemResult.state)
//                fail("Wrong state on repetition " + i + ": " + itemResult + ", " + itemResult.errors +
//                        " \r\ncontract_errors: " + contract.getErrors());
//
//            assertEquals(ItemState.APPROVED, itemResult.state);
//        }
//    }

    private void addDetailsToAllLedgers(Contract contract) {
        HashId id;
        StateRecord orCreate;
        for (Approvable c : contract.getRevokingItems()) {
            id = c.getId();
            for (Node nodeS : nodes) {
                orCreate = nodeS.getLedger().findOrCreate(id);
                orCreate.setState(ItemState.APPROVED).save();
            }
        }

        destroyFromAllNodesExistingNew(contract);

        destroyCurrentFromAllNodesIfExists(contract);
    }

    private void destroyFromAllNodesExistingNew(Contract c50_1) {
        StateRecord orCreate;
        for (Approvable c : c50_1.getNewItems()) {
            for (Node nodeS : nodes) {
                orCreate = nodeS.getLedger().getRecord(c.getId());
                if (orCreate != null)
                    orCreate.destroy();
            }
        }
    }

    @Test
    public void registerBadItem() throws Exception {
        TestItem bad = new TestItem(false);
        node.registerItem(bad);
        ItemResult r = node.waitItem(bad.getId(), 500);
        assertEquals(ItemState.DECLINED, r.state);
    }

    @Test
    public void checkItem() throws Exception {
        TestItem ok = new TestItem(true);
        TestItem bad = new TestItem(false);
        node.registerItem(ok);
        node.registerItem(bad);
        node.waitItem(ok.getId(), 2000);
        node.waitItem(bad.getId(), 2000);
        assertEquals(ItemState.APPROVED, node.checkItem(ok.getId()).state);
        assertEquals(ItemState.DECLINED, node.checkItem(bad.getId()).state);
    }


    @Test
    public void shouldCreateItems() throws Exception {
        TestItem item = new TestItem(true);

        node.registerItem(item);
        ItemResult result = node.waitItem(item.getId(), 1000);
        assertEquals(ItemState.APPROVED, result.state);

        result = node.waitItem(item.getId(), 1000);
        assertEquals(ItemState.APPROVED, result.state);
        result = node.waitItem(item.getId(), 1000);
        assertEquals(ItemState.APPROVED, result.state);

        result = node.checkItem(item.getId());
        assertEquals(ItemState.APPROVED, result.state);
    }

    @Test
    public void shouldDeclineItems() throws Exception {
        TestItem item = new TestItem(false);

        node.registerItem(item);
        ItemResult result = node.waitItem(item.getId(), 1000);
        assertEquals(ItemState.DECLINED, result.state);

        result = node.waitItem(item.getId(), 1000);
        assertEquals(ItemState.DECLINED, result.state);
        result = node.waitItem(item.getId(), 1000);
        assertEquals(ItemState.DECLINED, result.state);

        result = node.checkItem(item.getId());
        assertEquals(ItemState.DECLINED, result.state);
    }

    @Test
    public void singleNodeMixApprovedAndDeclined() throws Exception {
        TestItem item = new TestItem(true);

        node.registerItem(item);
        ItemResult result = node.waitItem(item.getId(), 2000);
        assertEquals(ItemState.APPROVED, result.state);

        result = node.waitItem(item.getId(), 2000);
        assertEquals(ItemState.APPROVED, result.state);
        result = node.waitItem(item.getId(), 2000);
        assertEquals(ItemState.APPROVED, result.state);

        result = node.checkItem(item.getId());
        assertEquals(ItemState.APPROVED, result.state);


        // Negative consensus
        TestItem item2 = new TestItem(false);

        node.registerItem(item2);
        ItemResult result2 = node.waitItem(item2.getId(), 2000);
        assertEquals(ItemState.DECLINED, result2.state);

        result2 = node.waitItem(item2.getId(), 2000);
        assertEquals(ItemState.DECLINED, result2.state);
        result2 = node.waitItem(item2.getId(), 2000);
        assertEquals(ItemState.DECLINED, result2.state);

        result2 = node.checkItem(item2.getId());
        assertEquals(ItemState.DECLINED, result2.state);
    }


    @Test
    public void testNotCreatingOnReject() throws Exception {
        TestItem main = new TestItem(false);
        TestItem new1 = new TestItem(true);
        TestItem new2 = new TestItem(true);

        main.addNewItems(new1, new2);

        assertEquals(2, main.getNewItems().size());

        node.registerItem(main);

        ItemResult itemResult = node.waitItem(main.getId(), 2000);

        assertEquals(ItemState.DECLINED, itemResult.state);

        @NonNull ItemResult itemNew1 = node.checkItem(new1.getId());
        assertEquals(ItemState.UNDEFINED, itemNew1.state);

        @NonNull ItemResult itemNew2 = node.checkItem(new2.getId());
        assertEquals(ItemState.UNDEFINED, itemNew2.state);
    }

    @Test
    public void rejectBadNewItem() throws Exception {

//        LogPrinter.showDebug(true);

        TestItem main = new TestItem(true);
        TestItem new1 = new TestItem(true);
        TestItem new2 = new TestItem(false);

        main.addNewItems(new1, new2);

        assertEquals(2, main.getNewItems().size());

        node.registerItem(main);
        ItemResult itemResult = node.waitItem(main.getId(), 2000);

        assertEquals(ItemState.DECLINED, itemResult.state);

        @NonNull ItemResult itemNew1 = node.checkItem(new1.getId());
        assertEquals(ItemState.UNDEFINED, itemNew1.state);

        @NonNull ItemResult itemNew2 = node.checkItem(new2.getId());
        assertEquals(ItemState.UNDEFINED, itemNew2.state);
    }

    @Test
    public void badNewDocumentsPreventAccepting() throws Exception {

//        LogPrinter.showDebug(true);

        TestItem main = new TestItem(true);
        TestItem new1 = new TestItem(true);
        TestItem new2 = new TestItem(true);

        // and now we run the day for teh output document:
        node.registerItem(new2);

        // and this one was created before
        @NonNull ItemResult itemNew2before = node.waitItem(new2.getId(), 2000);
        assertEquals(ItemState.APPROVED, itemNew2before.state);

        main.addNewItems(new1, new2);

        assertEquals(2, main.getNewItems().size());

        @NonNull ItemResult item = node.checkItem(main.getId());
        assertEquals(ItemState.UNDEFINED, item.state);

        node.registerItem(main);

        ItemResult itemResult = node.waitItem(main.getId(), 2000);
        assertEquals(ItemState.DECLINED, itemResult.state);

        @NonNull ItemResult itemNew1 = node.waitItem(new1.getId(), 2000);
        assertEquals(ItemState.UNDEFINED, itemNew1.state);

        // and this one was created before
        @NonNull ItemResult itemNew2 = node.waitItem(new2.getId(), 2000);
        assertEquals(ItemState.APPROVED, itemNew2.state);

        LogPrinter.showDebug(false);
    }

//    @Test
//    public void acceptWithReferences() throws Exception {
//        TestItem main = new TestItem(true);
//        TestItem new1 = new TestItem(true);
//        TestItem new2 = new TestItem(true);
//
//        StateRecord existing1 = ledger.findOrCreate(HashId.createRandom());
//        existing1.setState(ItemState.APPROVED).save();
//        StateRecord existing2 = ledger.findOrCreate(HashId.createRandom());
//        existing2.setState(ItemState.LOCKED).save();
//
//        main.addReferencedItems(existing1.getId(), existing2.getId());
//        main.addNewItems(new1, new2);
//
//        main.addReferencedItems(existing1.getId(), existing2.getId());
//        main.addNewItems(new1, new2);
//
//        // check that main is fully approved
//        node.registerItem(main);
//
//        ItemResult itemResult = node.waitItem(main.getId(), 100);
//        assertEquals(ItemState.APPROVED, itemResult.state);
//
//        assertEquals(ItemState.APPROVED, node.checkItem(new1.getId()).state);
//        assertEquals(ItemState.APPROVED, node.checkItem(new2.getId()).state);
//
//        // and the references are intact
//        assertEquals(ItemState.APPROVED, node.checkItem(existing1.getId()).state);
//        assertEquals(ItemState.LOCKED, node.checkItem(existing2.getId()).state);
//    }

    @Test
    public void badReferencesDeclineListStates() throws Exception {

//        LogPrinter.showDebug(true);

        for (ItemState badState : Arrays.asList(
                ItemState.PENDING, ItemState.PENDING_POSITIVE, ItemState.PENDING_NEGATIVE, ItemState.UNDEFINED,
                ItemState.DECLINED, ItemState.REVOKED, ItemState.LOCKED_FOR_CREATION)
                ) {


            System.out.println("--------state " + badState + " ---------");

            TestItem main = new TestItem(true);

            StateRecord existing1 = ledger.findOrCreate(HashId.createRandom());
            existing1.setState(ItemState.APPROVED).save();

            // but second is not good
            StateRecord existing2 = ledger.findOrCreate(HashId.createRandom());
            existing2.setState(badState).save();

            main.addReferencedItems(existing1.getId(), existing2.getId());

            // check that main is fully approved
            node.registerItem(main);
            ItemResult itemResult = node.waitItem(main.getId(), 5000);
            assertEquals(ItemState.DECLINED, itemResult.state);

            Thread.sleep(500);

            // and the references are intact

            while(ItemState.APPROVED != existing1.getState()) {
                Thread.sleep(500);
                System.out.println(existing1.reload().getState());
            }
            assertEquals(ItemState.APPROVED, existing1.getState());

            while (badState != existing2.getState()) {
                Thread.sleep(500);
                System.out.println(existing2.reload().getState());
            }
            assertEquals(badState, existing2.getState());
        }
    }

    @Test
    public void badReferencesDecline() throws Exception {

//        LogPrinter.showDebug(true);

        TestItem main = new TestItem(true);
        TestItem new1 = new TestItem(true);
        TestItem new2 = new TestItem(true);


        TestItem existing1 = new TestItem(false);
        TestItem existing2 = new TestItem(true);

        System.out.println("--------resister (bad) item " + existing1.getId() + " ---------");
        node.registerItem(existing1);

        @NonNull ItemResult existing1Before = node.waitItem(existing1.getId(), 2000);
        assertEquals(ItemState.DECLINED, existing1Before.state);

        System.out.println("--------resister (good) item " + existing2.getId() + " ---------");
        node.registerItem(existing2);

        @NonNull ItemResult existing2Before = node.waitItem(existing2.getId(), 2000);
        assertEquals(ItemState.APPROVED, existing2Before.state);

        main.addReferencedItems(existing1.getId(), existing2.getId());
        main.addNewItems(new1, new2);

        System.out.println("--------resister (main) item " + main.getId() + " ---------");

        // check that main is fully approved
        node.registerItem(main);

        ItemResult itemResult = node.waitItem(main.getId(), 5000);
        assertEquals(ItemState.DECLINED, itemResult.state);

        assertEquals(ItemState.UNDEFINED, node.checkItem(new1.getId()).state);
        assertEquals(ItemState.UNDEFINED, node.checkItem(new2.getId()).state);

        // and the references are intact
        assertEquals(ItemState.DECLINED, node.checkItem(existing1.getId()).state);
        assertEquals(ItemState.APPROVED, node.checkItem(existing2.getId()).state);

//        LogPrinter.showDebug(false);
    }

    @Test
    public void missingReferencesDecline() throws Exception {

//        LogPrinter.showDebug(true);

        TestItem main = new TestItem(true);

        TestItem existing = new TestItem(true);
        node.registerItem(existing);
        @NonNull ItemResult existingItem = node.waitItem(existing.getId(), 2000);

        // but second is missing
        HashId missingId = HashId.createRandom();

        main.addReferencedItems(existing.getId(), missingId);

        // check that main is declined
        System.out.println("--------- missind id: " + missingId);
        System.out.println("--------- existing id: " + existing.getId());
        node.registerItem(main);
        // need some time to resync missingId
        ItemResult itemResult = node.waitItem(main.getId(), 5000);
        assertEquals(ItemState.DECLINED, itemResult.state);

        // and the references are intact
        assertEquals(ItemState.APPROVED, existingItem.state);

        System.out.println(node.getItem(missingId));

        assertNull(node.getItem(missingId));
    }

    @Test
    public void approveAndRevoke() throws Exception {
        return;
//        TestItem main = new TestItem(true);
//
//        StateRecord existing1 = ledger.findOrCreate(HashId.createRandom());
//        existing1.setState(ItemState.APPROVED).save();
//        StateRecord existing2 = ledger.findOrCreate(HashId.createRandom());
//        existing2.setState(ItemState.APPROVED).save();
//
//        main.addRevokingItems(new FakeItem(existing1), new FakeItem(existing2));
//
//         check that main is fully approved
//        node.registerItem(main);
//        ItemResult itemResult = node.waitItem(main.getId(), 100);
//        assertEquals(ItemState.APPROVED, itemResult.state);
//
//         and the references are intact
//        assertEquals(ItemState.REVOKED, node.checkItem(existing1.getId()).state);
//        assertEquals(ItemState.REVOKED, node.checkItem(existing2.getId()).state);
    }

    @Test(timeout = 15000)
    public void badRevokingItemsDeclineAndRemoveLock() throws Exception {

//        LogPrinter.showDebug(true);
        // todo: check LOCKED situation

        for (ItemState badState : Arrays.asList(
                ItemState.PENDING, ItemState.PENDING_POSITIVE, ItemState.PENDING_NEGATIVE, ItemState.UNDEFINED,
                ItemState.DECLINED, ItemState.REVOKED, ItemState.LOCKED_FOR_CREATION)
                ) {

            Thread.sleep(200);
            System.out.println("--------state " + badState + " ---------");

            TestItem main = new TestItem(true);

            StateRecord existing1 = ledger.findOrCreate(HashId.createRandom());
            existing1.setState(ItemState.APPROVED).save();
            // but second is not good
            StateRecord existing2 = ledger.findOrCreate(HashId.createRandom());
            existing2.setState(badState).save();

            Thread.sleep(200);

            main.addRevokingItems(new FakeItem(existing1), new FakeItem(existing2));

            node.registerItem(main);
            ItemResult itemResult = node.waitItem(main.getId(), 5000);
            assertEquals(ItemState.DECLINED, itemResult.state);

            // and the references are intact
            while(ItemState.APPROVED != existing1.getState()) {
                Thread.sleep(500);
                System.out.println(existing1.reload().getState());
            }
            assertEquals(ItemState.APPROVED, existing1.getState());

            while (badState != existing2.getState()) {
                Thread.sleep(500);
                System.out.println(existing2.reload().getState());
            }
            assertEquals(badState, existing2.getState());

        }
    }

//    @Test
//    public void itemsCachedThenPurged() throws Exception {
//        config.setMaxElectionsTime(Duration.ofMillis(100));
//
//        TestItem main = new TestItem(true);
//        main.setExpiresAtPlusFive(false);
//
//        node.registerItem(main);
//        ItemResult itemResult = node.waitItem(main.getId(), 3000);
//        assertEquals(ItemState.APPROVED, itemResult.state);
//
//        assertEquals(main, node.getItem(main.getId()));
//        Thread.sleep(1200);
//        assertEquals(ItemState.UNDEFINED, node.checkItem(main.getId()).state);
//    }

    @Test
    public void createRealContract() throws Exception {
        Contract c = Contract.fromDslFile(ROOT_PATH + "simple_root_contract.yml");
        c.addSignerKeyFromFile(ROOT_PATH + "_xer0yfe2nn1xthc.private.unikey");
        assertTrue(c.check());
        c.seal();

        node.registerItem(c);
        ItemResult itemResult = node.waitItem(c.getId(), 1500);
        assertEquals(ItemState.APPROVED, itemResult.state);
    }


    public void registerAndCheckApproved(Contract c) throws TimeoutException, InterruptedException {
        System.out.println("registerAndCheckApproved " + this);
        System.out.println("registerAndCheckApproved " + node + " " + nodes);
        node.registerItem(c);
        ItemResult itemResult = node.waitItem(c.getId(), 5000);
        assertEquals(ItemState.APPROVED, itemResult.state);
    }

    public void registerAndCheckDeclined(Contract c) throws TimeoutException, InterruptedException {
        node.registerItem(c);
        ItemResult itemResult = node.waitItem(c.getId(), 5000);
        assertEquals(ItemState.DECLINED, itemResult.state);
    }

    @Test
    public void checkRegisterContractOnLostPacketsNetwork() throws Exception {

        network.setTest_nodeBeingOffedChance(75);

        AsyncEvent ae = new AsyncEvent();

        Contract contract = Contract.fromDslFile(ROOT_PATH + "coin100.yml");
        contract.addSignerKeyFromFile(ROOT_PATH +"_xer0yfe2nn1xthc.private.unikey");
        contract.seal();

        addDetailsToAllLedgers(contract);

        contract.check();
        contract.traceErrors();
        assertTrue(contract.isOk());

        node.registerItem(contract);

        Timer timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {

                System.out.println("-----------nodes state--------------");

                boolean all_is_approved = true;
                for (Node n : nodes) {
                    ItemResult r = n.checkItem(contract.getId());
                    System.out.println("Node: " + n.toString() + " state: " + r.state);
                    if(r.state != ItemState.APPROVED) {
                        all_is_approved = false;
                    }
                }

                if(all_is_approved) ae.fire();
            }
        }, 0, 1000);

        boolean time_is_up = false;
        try {
            ae.await(45000);
        } catch (TimeoutException e) {
            time_is_up = true;
            System.out.println("time is up");
        }

        timer.cancel();

        network.setTest_nodeBeingOffedChance(0);

        assertFalse(time_is_up);
    }

    @Test
    public void checkRegisterContractOnTemporaryOffedNetwork() throws Exception {

        // switch off half network
        for (int i = 0; i < NODES/2; i++) {
            network.switchOffNodeTestMode(nodes.get(NODES-i-1));
        }

        AsyncEvent ae = new AsyncEvent();

        Contract contract = Contract.fromDslFile(ROOT_PATH + "coin100.yml");
        contract.addSignerKeyFromFile(ROOT_PATH +"_xer0yfe2nn1xthc.private.unikey");
        contract.seal();

        addDetailsToAllLedgers(contract);

        contract.check();
        contract.traceErrors();
        assertTrue(contract.isOk());

//        LogPrinter.showDebug(true);

        node.registerItem(contract);

        Timer timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {

                System.out.println("-----------nodes state--------------");

                boolean all_is_approved = true;
                for (Node n : nodes) {
                    ItemResult r = n.checkItem(contract.getId());
                    System.out.println("Node: " + n.toString() + " state: " + r.state);
                    if(r.state != ItemState.APPROVED) {
                        all_is_approved = false;
                    }
                }
                assertEquals(all_is_approved, false);
            }
        }, 0, 1000);

        // wait and now switch on full network
        try {
            ae.await(5000);
        } catch (TimeoutException e) {
            timer.cancel();
            System.out.println("switching on network");
            network.switchOnAllNodesTestMode();
        }

        Timer timer2 = new Timer();
        timer2.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {

                System.out.println("-----------nodes state--------------");

                boolean all_is_approved = true;
                for (Node n : nodes) {
                    ItemResult r = n.checkItem(contract.getId());
                    System.out.println("Node: " + n.toString() + " state: " + r.state);

                    if(r.state != ItemState.APPROVED) {
                        all_is_approved = false;
                    }

                    if(all_is_approved) {
                        ae.fire();
                    }
                }
            }
        }, 0, 1000);

        try {
            ae.await(5000);
        } catch (TimeoutException e) {
            System.out.println("time is up");
        }

        timer2.cancel();

        boolean all_is_approved = true;
        for (Node n : nodes) {
            ItemResult r = n.waitItem(contract.getId(), 2000);
            if(r.state != ItemState.APPROVED) {
                all_is_approved = false;
            }
        }

        LogPrinter.showDebug(false);

        assertEquals(all_is_approved, true);


    }


    /////////////////////////////// from Node2Single

    @Test
    public void acceptWithReferences() throws Exception {
        return;
    }


    @Test
    public void shouldDeclineSplit() throws Exception {
        // 100
        Contract c = Contract.fromDslFile(ROOT_PATH + "coin100.yml");
        c.addSignerKeyFromFile(ROOT_PATH +"_xer0yfe2nn1xthc.private.unikey");
        assertTrue(c.check());
        c.seal();


        registerAndCheckApproved(c);

        // 50
        c = c.createRevision();
        Contract c2 = c.splitValue("amount", new Decimal(550));
        c2.addSignerKeyFromFile(ROOT_PATH +"_xer0yfe2nn1xthc.private.unikey");
        assertFalse(c2.check());
        c2.seal();
        assertEquals(new Decimal(-450), c.getStateData().get("amount"));

        registerAndCheckDeclined(c2);
    }

    @Test
    public void shouldApproveSplit() throws Exception {
        // 100
        Contract c = Contract.fromDslFile(ROOT_PATH + "coin100.yml");
        c.addSignerKeyFromFile(ROOT_PATH +"_xer0yfe2nn1xthc.private.unikey");
        assertTrue(c.check());
        c.seal();


        registerAndCheckApproved(c);

        // 50
        c = c.createRevision();
        Contract c2 = c.splitValue("amount", new Decimal(50));
        c2.addSignerKeyFromFile(ROOT_PATH +"_xer0yfe2nn1xthc.private.unikey");
        assertTrue(c2.check());
        c2.seal();
        assertEquals(new Decimal(50), c.getStateData().get("amount"));

        registerAndCheckApproved(c2);
        assertEquals("50", c2.getStateData().get("amount"));
    }

    @Test
    public void shouldBreakByQuantizer() throws Exception {
        // 100
        Contract.setTestQuantaLimit(10);
        Contract c = Contract.fromDslFile(ROOT_PATH + "coin100.yml");
        c.addSignerKeyFromFile(ROOT_PATH +"_xer0yfe2nn1xthc.private.unikey");
//        assertTrue(c.check());
        c.seal();

        node.registerItem(c);
        ItemResult itemResult = node.waitItem(c.getId(), 1500);
        System.out.println(itemResult);
        Contract.setTestQuantaLimit(-1);

        assertEquals(ItemState.UNDEFINED, itemResult.state);
    }

    @Test
    public void shouldBreakByQuantizerSplit() throws Exception {
        // 100
        Contract c = Contract.fromDslFile(ROOT_PATH + "coin100.yml");
        c.addSignerKeyFromFile(ROOT_PATH +"_xer0yfe2nn1xthc.private.unikey");
//        assertTrue(c.check());
        c.seal();

        registerAndCheckApproved(c);


        Contract.setTestQuantaLimit(60);
        // 50
        Contract forSplit = c.createRevision();
        Contract c2 = forSplit.splitValue("amount", new Decimal(30));
        c2.addSignerKeyFromFile(ROOT_PATH +"_xer0yfe2nn1xthc.private.unikey");
//        assertTrue(c2.check());
        c2.seal();
        forSplit.seal();
        assertEquals(new Decimal(30), new Decimal(Long.valueOf(c2.getStateData().get("amount").toString())));
        assertEquals(new Decimal(70), forSplit.getStateData().get("amount"));

        node.registerItem(forSplit);
        ItemResult itemResult = node.waitItem(forSplit.getId(), 1500);
        System.out.println(itemResult);
        Contract.setTestQuantaLimit(-1);

        assertEquals(ItemState.UNDEFINED, itemResult.state);
    }

    @Test
    public void shouldApproveSplitAndJoinWithNewSend() throws Exception {
        // 100
        Contract c = Contract.fromDslFile(ROOT_PATH + "coin100.yml");
        c.addSignerKeyFromFile(ROOT_PATH +"_xer0yfe2nn1xthc.private.unikey");
        assertTrue(c.check());
        c.seal();


        registerAndCheckApproved(c);
        assertEquals(100, c.getStateData().get("amount"));


        // 50
        Contract cRev = c.createRevision();
        Contract c2 = cRev.splitValue("amount", new Decimal(50));
        c2.addSignerKeyFromFile(ROOT_PATH +"_xer0yfe2nn1xthc.private.unikey");
        assertTrue(c2.check());
        c2.seal();
        assertEquals(new Decimal(50), cRev.getStateData().get("amount"));

        registerAndCheckApproved(c2);
        assertEquals("50", c2.getStateData().get("amount"));


        //send 150 out of 2 contracts (100 + 50)
        Contract c3 = c2.createRevision();
        c3.getStateData().set("amount", (new Decimal((Integer)c.getStateData().get("amount"))).
                add(new Decimal(Integer.valueOf((String)c3.getStateData().get("amount")))));
        c3.addSignerKeyFromFile(ROOT_PATH +"_xer0yfe2nn1xthc.private.unikey");
        c3.addRevokingItems(c);
        assertTrue(c3.check());
        c3.seal();

        registerAndCheckApproved(c3);
        assertEquals(new Decimal(150), c3.getStateData().get("amount"));
    }

    @Test
    public void shouldDeclineSplitAndJoinWithWrongAmount() throws Exception {
        // 100
        Contract c = Contract.fromDslFile(ROOT_PATH + "coin100.yml");
        c.addSignerKeyFromFile(ROOT_PATH +"_xer0yfe2nn1xthc.private.unikey");
        assertTrue(c.check());
        c.seal();

        System.out.println("------------ register base contract -------------");
        registerAndCheckApproved(c);
        assertEquals(100, c.getStateData().get("amount"));


        // 50
        Contract cRev = c.createRevision();
        Contract c2 = cRev.splitValue("amount", new Decimal(50));
        c2.addSignerKeyFromFile(ROOT_PATH +"_xer0yfe2nn1xthc.private.unikey");
        assertTrue(c2.check());
        c2.seal();
        assertEquals(new Decimal(50), cRev.getStateData().get("amount"));

        System.out.println("------------ register split contract -------------");
        registerAndCheckApproved(c2);
        assertEquals("50", c2.getStateData().get("amount"));


        //wrong. send 500 out of 2 contracts (100 + 50)
        Contract c3 = c2.createRevision();
        c3.getStateData().set("amount", new Decimal(500));
        c3.addSignerKeyFromFile(ROOT_PATH +"_xer0yfe2nn1xthc.private.unikey");
        c3.addRevokingItems(c);
        assertFalse(c3.check());
        c3.seal();

        System.out.println("------------ register join contract -------------");
        registerAndCheckDeclined(c3);
    }

    @Test
    public void itemsCachedThenPurged() throws Exception {

        // todo: rewrite
//        config.setMaxElectionsTime(Duration.ofMillis(100));
//
//        TestItem main = new TestItem(true);
//        main.setExpiresAtPlusFive(false);
//
//        node.registerItem(main);
//        ItemResult itemResult = node.waitItem(main.getId(), 1500);
//        assertEquals(ItemState.APPROVED, itemResult.state);
//        assertEquals(ItemState.UNDEFINED, node.checkItem(main.getId()).state);
//
//        assertEquals(main, node.getItem(main.getId()));
//        Thread.sleep(500);
//        assertEquals(ItemState.UNDEFINED, node.checkItem(main.getId()).state);
    }

    @Test
    public void timeoutError() throws Exception {

        Duration maxElectionsTime = config.getMaxElectionsTime();
        config.setMaxElectionsTime(Duration.ofMillis(200));

        TestItem item = new TestItem(true);

        // We start elections but no node in the network know the source, so it
        // will short-circuit to self and then stop by the timeout:

        ItemResult itemResult = node.checkItem(item.getId());
        assertEquals(ItemState.UNDEFINED, itemResult.state);
        assertFalse(itemResult.haveCopy);
        assertNull(itemResult.createdAt);
        assertNull(itemResult.expiresAt);

        itemResult = node.waitItem(item.getId(), 100);
        assertEquals(ItemState.UNDEFINED, itemResult.state);

        itemResult = node.checkItem(item.getId());
        assertEquals(ItemState.UNDEFINED, itemResult.state);

        config.setMaxElectionsTime(maxElectionsTime);
    }


}