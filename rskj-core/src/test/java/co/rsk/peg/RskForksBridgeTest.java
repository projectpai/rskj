/*
 * This file is part of RskJ
 * Copyright (C) 2017 RSK Labs Ltd.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package co.rsk.peg;

import co.rsk.bitcoinj.core.Address;
import co.rsk.bitcoinj.core.AddressFormatException;
import co.rsk.bitcoinj.core.Coin;
import co.rsk.bitcoinj.params.RegTestParams;
import co.rsk.config.RskSystemProperties;
import co.rsk.core.RskAddress;
import co.rsk.core.bc.BlockChainImpl;
import co.rsk.test.World;
import co.rsk.test.builders.BlockBuilder;
import org.ethereum.config.BlockchainNetConfig;
import org.ethereum.config.blockchain.RegTestConfig;
import org.ethereum.core.*;
import org.ethereum.crypto.ECKey;
import org.ethereum.vm.PrecompiledContracts;
import org.ethereum.vm.program.ProgramResult;
import org.ethereum.vm.program.invoke.ProgramInvokeFactoryImpl;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.spongycastle.util.encoders.Hex;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RskForksBridgeTest {
    private static BlockchainNetConfig blockchainNetConfigOriginal;
    private static RskSystemProperties config;

    @BeforeClass
    public static void setUpBeforeClass() throws Exception {
        config = new RskSystemProperties();
        config.setBlockchainConfig(new RegTestConfig());
    }

    private Repository repository;
    private ECKey keyHoldingRSKs;
    private ECKey whitelistManipulationKey;
    private Genesis genesis;
    private BlockChainImpl blockChain;
    private Block blockBase;

    @Before
    public void before() throws IOException, ClassNotFoundException {
        World world = new World();
        blockChain = world.getBlockChain();
        repository = blockChain.getRepository();

        whitelistManipulationKey = ECKey.fromPrivate(Hex.decode("3890187a3071327cee08467ba1b44ed4c13adb2da0d5ffcc0563c371fa88259c"));

        genesis = (Genesis)blockChain.getBestBlock();
        keyHoldingRSKs = new ECKey();
        co.rsk.core.Coin balance = new co.rsk.core.Coin(new BigInteger("10000000000000000000"));
        repository.addBalance(new RskAddress(keyHoldingRSKs.getAddress()), balance);
        genesis.setStateRoot(repository.getRoot());
        genesis.flushRLP();

        blockChain.getBlockStore().saveBlock(genesis, genesis.getCumulativeDifficulty(), true);

        Transaction whitelistAddressTx = buildWhitelistTx();
        Transaction receiveHeadersTx = buildReceiveHeadersTx();
        Transaction registerBtctransactionTx = buildRegisterBtcTransactionTx();

        blockBase = buildBlock(genesis, whitelistAddressTx, receiveHeadersTx, registerBtctransactionTx);
        Assert.assertEquals(ImportResult.IMPORTED_BEST, blockChain.tryToConnect(blockBase));
    }

    @Test
    public void testNoFork() throws Exception {
        Transaction releaseTx = buildReleaseTx();
        Block blockB1 = buildBlock(blockBase, releaseTx);
        blockB1.seal();
        Assert.assertEquals(ImportResult.IMPORTED_BEST, blockChain.tryToConnect(blockB1));
        assertReleaseTransactionState(ReleaseTransactionState.WAITING_FOR_SELECTION);

        Block blockB2 = buildBlock(blockB1);
        blockB1.seal();
        Assert.assertEquals(ImportResult.IMPORTED_BEST, blockChain.tryToConnect(blockB2));
        assertReleaseTransactionState(ReleaseTransactionState.WAITING_FOR_SELECTION);

        Transaction updateCollectionsTx = buildUpdateCollectionsTx();
        Block blockB3 = buildBlock(blockB2, updateCollectionsTx);
        Assert.assertEquals(ImportResult.IMPORTED_BEST, blockChain.tryToConnect(blockB3));
        assertReleaseTransactionState(ReleaseTransactionState.WAITING_FOR_CONFIRMATIONS);
    }

    @Test
    public void testLosingForkBuiltFirst() throws Exception {
        Transaction releaseTx = buildReleaseTx();

        Block blockA1 = buildBlock(blockBase, 4l);
        Assert.assertEquals(ImportResult.IMPORTED_BEST, blockChain.tryToConnect(blockA1));
        assertReleaseTransactionState(ReleaseTransactionState.NO_TX);

        Block blockA2 = buildBlock(blockA1, 6l, releaseTx);
        Assert.assertEquals(ImportResult.IMPORTED_BEST, blockChain.tryToConnect(blockA2));
        assertReleaseTransactionState(ReleaseTransactionState.WAITING_FOR_SELECTION);

        Block blockB1 = buildBlock(blockBase, 1l, releaseTx);
        Assert.assertEquals(ImportResult.IMPORTED_NOT_BEST, blockChain.tryToConnect(blockB1));
        assertReleaseTransactionState(ReleaseTransactionState.WAITING_FOR_SELECTION);

        Block blockB2 = buildBlock(blockB1, 2l);
        Assert.assertEquals(ImportResult.IMPORTED_NOT_BEST, blockChain.tryToConnect(blockB2));
        assertReleaseTransactionState(ReleaseTransactionState.WAITING_FOR_SELECTION);

        Transaction updateCollectionsTx = buildUpdateCollectionsTx();
        Block blockB3 = buildBlock(blockB2, 10l, updateCollectionsTx);
        Assert.assertEquals(ImportResult.IMPORTED_BEST, blockChain.tryToConnect(blockB3));
        assertReleaseTransactionState(ReleaseTransactionState.WAITING_FOR_CONFIRMATIONS);
    }


    @Test
    public void testWinningForkBuiltFirst() throws Exception {
        Transaction releaseTx = buildReleaseTx();

        Block blockB1 = buildBlock(blockBase, releaseTx);
        Assert.assertEquals(ImportResult.IMPORTED_BEST, blockChain.tryToConnect(blockB1));
        assertReleaseTransactionState(ReleaseTransactionState.WAITING_FOR_SELECTION);

        Block blockB2 = buildBlock(blockB1,6l);
        Assert.assertEquals(ImportResult.IMPORTED_BEST, blockChain.tryToConnect(blockB2));
        assertReleaseTransactionState(ReleaseTransactionState.WAITING_FOR_SELECTION);

        Block blockA1 = buildBlock(blockBase,1);
        Assert.assertEquals(ImportResult.IMPORTED_NOT_BEST, blockChain.tryToConnect(blockA1));
        assertReleaseTransactionState(ReleaseTransactionState.WAITING_FOR_SELECTION);

        Block blockA2 = buildBlock(blockA1,1, releaseTx);
        Assert.assertEquals(ImportResult.IMPORTED_NOT_BEST, blockChain.tryToConnect(blockA2));
        assertReleaseTransactionState(ReleaseTransactionState.WAITING_FOR_SELECTION);

        Transaction updateCollectionsTx = buildUpdateCollectionsTx();
        Block blockB3 = buildBlock(blockB2,12, updateCollectionsTx);
        Assert.assertEquals(ImportResult.IMPORTED_BEST, blockChain.tryToConnect(blockB3));
        assertReleaseTransactionState(ReleaseTransactionState.WAITING_FOR_CONFIRMATIONS);
    }

    @Test
    public void testReleaseTxJustInLoosingFork() throws Exception {
        Transaction releaseTx = buildReleaseTx();

        Block blockA1 = buildBlock(blockBase, releaseTx);
        Assert.assertEquals(ImportResult.IMPORTED_BEST, blockChain.tryToConnect(blockA1));
        assertReleaseTransactionState(ReleaseTransactionState.WAITING_FOR_SELECTION);

        Block blockA2 = buildBlock(blockA1,3);
        Assert.assertEquals(ImportResult.IMPORTED_BEST, blockChain.tryToConnect(blockA2));
        assertReleaseTransactionState(ReleaseTransactionState.WAITING_FOR_SELECTION);

        Block blockB1 = buildBlock(blockBase,2);
        Assert.assertEquals(ImportResult.IMPORTED_NOT_BEST, blockChain.tryToConnect(blockB1));
        assertReleaseTransactionState(ReleaseTransactionState.WAITING_FOR_SELECTION);

        Block blockB2 = buildBlock(blockB1,1);
        Assert.assertEquals(ImportResult.IMPORTED_NOT_BEST, blockChain.tryToConnect(blockB2));
        assertReleaseTransactionState(ReleaseTransactionState.WAITING_FOR_SELECTION);

        Transaction updateCollectionsTx = buildUpdateCollectionsTx();
        Block blockB3 = buildBlock(blockB2,6l, updateCollectionsTx);
        Assert.assertEquals(ImportResult.IMPORTED_BEST, blockChain.tryToConnect(blockB3));
        assertReleaseTransactionState(ReleaseTransactionState.NO_TX);
    }

    @Test
    public void testReleaseTxJustInWinningFork() throws Exception {
        Transaction releaseTx = buildReleaseTx();

        Block blockA1 = buildBlock(blockBase, 4l);
        Assert.assertEquals(ImportResult.IMPORTED_BEST, blockChain.tryToConnect(blockA1));
        assertReleaseTransactionState(ReleaseTransactionState.NO_TX);

        Block blockA2 = buildBlock(blockA1, 5l);
        Assert.assertEquals(ImportResult.IMPORTED_BEST, blockChain.tryToConnect(blockA2));
        assertReleaseTransactionState(ReleaseTransactionState.NO_TX);

        Block blockB1 = buildBlock(blockBase, 1l, releaseTx);
        Assert.assertEquals(ImportResult.IMPORTED_NOT_BEST, blockChain.tryToConnect(blockB1));
        assertReleaseTransactionState(ReleaseTransactionState.NO_TX);

        Block blockB2 = buildBlock(blockB1, 1l);
        Assert.assertEquals(ImportResult.IMPORTED_NOT_BEST, blockChain.tryToConnect(blockB2));
        assertReleaseTransactionState(ReleaseTransactionState.NO_TX);

        Transaction updateCollectionsTx = buildUpdateCollectionsTx();
        Block blockB3 = buildBlock(blockB2, 10l, updateCollectionsTx);
        Assert.assertEquals(ImportResult.IMPORTED_BEST, blockChain.tryToConnect(blockB3));
        assertReleaseTransactionState(ReleaseTransactionState.WAITING_FOR_CONFIRMATIONS);
    }

    private Block buildBlock(Block parent, long difficulty) {
        World world = new World(blockChain, genesis);
        BlockBuilder blockBuilder = new BlockBuilder(world).difficulty(difficulty).parent(parent);
        Block block = blockBuilder.build();
        block.seal();
        return block;
    }

    private Block buildBlock(Block parent, Transaction ... txs) {
        Block block = buildBlock(parent, parent.getDifficulty().asBigInteger().longValue(), txs);
        block.seal();
        return block;
    }

    private Block buildBlock(Block parent, long difficulty, Transaction ... txs) {
        List<Transaction> txList = Arrays.asList(txs);
        World world = new World(blockChain, genesis);
        BlockBuilder blockBuilder = new BlockBuilder(world).difficulty(difficulty).parent(parent).transactions(txList).uncles(new ArrayList<>());
        Block block = blockBuilder.build();
        block.seal();
        return block;
    }

    private Transaction buildWhitelistTx() throws IOException, ClassNotFoundException {
        long nonce = 0;
        long value = 0;
        BigInteger gasPrice = BigInteger.valueOf(0);
        BigInteger gasLimit = BigInteger.valueOf(1000000);
        Transaction rskTx = CallTransaction.createCallTransaction(config, nonce, gasPrice.longValue(),
                gasLimit.longValue(), PrecompiledContracts.BRIDGE_ADDR, value,
                Bridge.ADD_LOCK_WHITELIST_ADDRESS, new Object[]{ "MZgZ1KF82STEWNhEpsEGo1Qeqgi6HJbFet", BigInteger.valueOf(Coin.COIN.multiply(4).value) });
        rskTx.sign(whitelistManipulationKey.getPrivKeyBytes());
        return rskTx;
    }


    private Transaction buildReceiveHeadersTx() throws IOException, ClassNotFoundException {
        String headers = "aced0005757200135b4c6a6176612e6c616e672e4f626a6563743b90ce589f1073296c02000078700000006e757200025b42acf317f8060854e002000078700000005103000000bbe7df1a2c064bda08764c15797ce664a030adb34bc82773785df148c936b747cb7ce5d0da9e660ea5e3032e281ac765ff4b13a944da0140b9bdca24e24f5090d861f156ffff7f2002000000007571007e0002000000510300000032c0a6482074f27d264d635523dad804217ee82bed71d3ae471bdcc0cd9f6223b5a2bccd28736bdfed3692fc905a7f2b44c09af87f1c4f9d7b22105573689d22d961f156ffff7f2001000000007571007e0002000000510300000045b656d80a12793c3f229371d1029d1aa1755f32b1d60643d2ccd45d9aaf4e311f7ea963a0f22a270a3574c9ed734f45b5b6d466131e6e870dafcdba8dddf52bd961f156ffff7f2007000000007571007e00020000005103000000ed9e76e776ef98c42dfece6c13b9917978b976fc484eaae9469c97bdbf5e2d03820e6fed0387d362c98439dd5d68ee4f6fd9bebe6c1b97f6c9136c4818baa689da61f156ffff7f2001000000007571007e000200000051030000005b03ab64eab9d87e1d58a1b05a2fda653f76e63c149144cf01b39eb3dfcaf437ba3b9c07d88687abae9fe34c73bddc14dfb55cba454a8c7c359b774a158400dada61f156ffff7f2001000000007571007e00020000005103000000686768d1423929e83224e330e2f5059c2980061abbf667f48112a8758aec1f5919d74e636f3dac921a64ef794ccabe36374f609a3b8faab4368f30d50a7f5825da61f156ffff7f2003000000007571007e00020000005103000000c92d017e8f08e4ce2fce154a96de81651258208943e1fa0592c3330ef6f6f0514a42d428a651d88522419491e7b6f3a055440daa7096bc50ac841da3c56a0ff7da61f156ffff7f2000000000007571007e000200000051030000001437958341aa71317c913a1d89334a49e3c89edc0af5ee996fe21a1c27ff392f7d0d854494d7316867e2acc8b83a373ab488798e013e21867a02542d0bc0d65cdb61f156ffff7f2001000000007571007e00020000005103000000ef89f1cc39b6e9d723a4915affda22b8c592c5d0779a6959e9dae9cea4761a5d065a91ceb292932dc89f1437f94f263b4dcc849653380c85e37983cbe9e2917bdb61f156ffff7f2001000000007571007e0002000000510300000090d7bd1c9b411e5347c416c988ea90108ff760b28b6efafa5bcf35ad527a161cdba893c6d0404c477520022db6b1812385a587fff74a93b2cdd65792b985573ddb61f156ffff7f2001000000007571007e0002000000510300000068e041f4375706dc3eea7a58c8474cf5d10719f0620985c67fd97884bd9dcb13a498ac41bade08b809e096302be853c9271606aada49453629915960eac3e68edb61f156ffff7f2001000000007571007e00020000005103000000d41670bb33bb1438ff176423187caf9238d6d69eca05f4e2efe2595310d53c2aa3aa8b85093f35afb74f6870e4bce8aa86301fb2e2b7b5cd308591c2e9c83450db61f156ffff7f2005000000007571007e00020000005103000000e5e4e2452191e32d8dcc15387602b5d9f3e1ffcae8bd6a02da7c6940a9778716b35a0a5e570bc4b35dad26a3a3640194eb7545df266a2d9056b8bffc3c9f995ddb61f156ffff7f2001000000007571007e000200000051030000004022b39c39ec37a49f36ba6c8e0d7f8799d6076309ba199b748d96972a1d2137d03fb162dd64a151c087f3bdd03d53dcc48b4b7fdaf249414c488de53fdcc8a1dc61f156ffff7f2003000000007571007e0002000000510300000016187e1d99f5759e2b7df08216fee2051018e0573fd99f2ae6b4d6ce32fd9909105692cb1b07fb6d12da26ad5ad340d3643f8b82501c0cace385de0e562c3b7bdc61f156ffff7f2005000000007571007e00020000005103000000edc62a4cb43ae4f83aa8e22fa9ee69862720d42d3019a822bced644540d8183eea9aed89fd9d764bf71c6b66e697c751da87317f184efae08fda79a73351dab5dc61f156ffff7f2003000000007571007e000200000051030000006aa08b8dd5dc95afedd7ac3ebcfe0d45fad1dce566a354813857980321a2612316a39524f5ad49046fe45b29d56e832508ea3f8f51a6f5804c17d89d38949dc3dc61f156ffff7f2000000000007571007e000200000051030000003df28079057f246a1c3a1a27ad8671ea8e6d6d05d7b0d44fb5ce5a5c7140053e34319fc6586bc50c42cd938bb1f7c523a30df44d790b774d3f336384084216a5dc61f156ffff7f2000000000007571007e00020000005103000000f587ea1d78927250b7dc6467844446675910c8c8fd376e2505a8257cb11d4c5105709404718dd138c79cc0159a84a14388449bb657526d58159e07f7df02f2fbdc61f156ffff7f2006000000007571007e00020000005103000000858eda7dd04a52103ef4dd3a6de29c9b2f250064196d3262bac86caaa6822b3d5893d4e79d714af298b231935cebb47e2ee480c74cded044966568bb529e8a95dd61f156ffff7f2005000000007571007e0002000000510300000093d2f03cc609fb50e55624997b3412f9e12410ee8fa882f48271f43b6e73b409d218f0755c0fb782ab4d1e62903ddcf5c7c90335600587f1666f11fe4fb9b97edd61f156ffff7f2000000000007571007e0002000000510300000010afe7dd160e38071da11caae04260027bc426ced23ef1532797f1f9bf59774db3e7598636b66ccdd6650ab9f45a066d430f73572dabe033eba155a3b70d866cdd61f156ffff7f2002000000007571007e000200000051030000009c52fc070b9a7ba6be81aaabff6dd5a5c25d19330747cc1b170a7aebaf3d0d58176f58d1f847fceb6fcf7041fcb21c5d54ea5c1ed39aa1294f36c2a409cccd72dd61f156ffff7f2006000000007571007e00020000005103000000e25622d39019c16a4290be09000b6e132b4588f213a584369ea7d5903c9f441211854ef542e4a234eef6ca89fa938934ba500d9cc79523b9944ed5975ef875c8dd61f156ffff7f2003000000007571007e000200000051030000006be9f4019cfcf37ef4be9fb412e9019550000dbfe7ccb959bd4e3726c48055758a7bfd2e664e4aaa31afce39f92e6624eea497be4e61b8f39da17c5aec0299b1dd61f156ffff7f2001000000007571007e00020000005103000000cdb0a1746ef7bf1934281108464a055823f872d45614660e0fa9cccd98a813742403b6c3ba9314597cc685f441e01f5ff8f767a8a3c68272c9300cf227506358de61f156ffff7f2003000000007571007e00020000005103000000ee00c0189b47a4135573578accd417c5b9016ee7750cef643d221a68cc65ec66f9892510cfd1aea4b1a8b7ebe3656e3ccfc6d08af0b3fbfdaeb13484fc25c241de61f156ffff7f2000000000007571007e000200000051030000003ee77eff8ccfab2fc91c7f8772283c80292a70d8d882bfb137edad7257c40659a60a25bee417bc21e6338f7361d51b5740c1973d849dafa97d29a13fe2cd6971de61f156ffff7f2006000000007571007e00020000005103000000115446528c600e2e8e4cb83876c1e1c70cc59e28b53358a69dc66c33ad45b74b1f38e42f64fd67a4a7865f2f138a6a0389d2d4aefab72ee9221a3f5241746930de61f156ffff7f2000000000007571007e000200000051030000009c320ac15a62a9ac805dd7b08bf6535dbfc6dc12c8319ac6de4eaa97907d563df862f539a796e91f5606225b18b508d4f469fde68158a0a62e84bc1753ded511de61f156ffff7f2000000000007571007e000200000051030000003ae4cf333e247e8a08dd870a351f049c00257c88fabd7ce311ff687774355134e8d5822aa8cb4cf6ee46ac86ed484ca0f870bdc7acce26e1b69df877a66bb31fde61f156ffff7f2002000000007571007e00020000005103000000d730fa85d355b4c59c138f559fe8e7ab5f0954a2755c98c9b5f5a31b16c6eb332b8a1e61bee737988fa1c9dbd4857ed54c6ebfbbe242dc937590613e63643228df61f156ffff7f2001000000007571007e00020000005103000000b21235641c415f46855c25b8ffe6439f67fc3d2d55012892efb92f7acfd62531b09ceca193791a6b622d10a1f04ecb34764feb90420433013148d63a80670e0adf61f156ffff7f2004000000007571007e000200000051030000006558bd17ac26f69b48622928043803760954ec1188c033632b390a1e664ed215e5e9bdb2d123671965f1d447cb47d4f87f6a3729f8d4dd2cdd756f9924927bc0df61f156ffff7f2001000000007571007e00020000005103000000fc5f99059ca4b1f5f32e19ae6f32cb9d6daa1de7d01f0b0d66f50219bb6ccd32dd4aae07274044d6a781a141ff286742dd098228f8cc5fe4265ddac2665e7801df61f156ffff7f2001000000007571007e00020000005103000000d33f58e43ed6481c042503783054e7ec5fdd4c3a0faeaac2118fa162c5efe336623b7ba0b501dedc3ed46bacebbe171d7cc49d91484a0c86f5da30e3ec781652df61f156ffff7f2003000000007571007e0002000000510300000022aa4494800ecba7450fe317201bf9172e5102029cfcbb0f34717129007e296ea95585518a4ac5c6f4e3ec94ba81cf1299c1060e5ab85466ce3ddca4c0bdac6cdf61f156ffff7f2001000000007571007e00020000005103000000fe1ce7938aea6deeada73ca3a7cc04d4534b23baf00c7f87b34640f282e4cf3571ae4174c9ab87f6cb51f1f6fa20fe441fe9d7969e1971d57cc74e6afb5792a6e061f156ffff7f2000000000007571007e000200000051030000001ef403358ebb7e8207296a0d55184c3909131137648bccd2eb9427c479fee768b2075f586656e4aa190115d8447685df88d778b0af7bcc2e29907e812dd41495e061f156ffff7f2001000000007571007e00020000005103000000cbd2222996a630778cafabe5e22c7801d4de4619a01e6451f2a861858884aa2c99b7aea464577f38dcfec08d7e6989ee3473111b61c4289ae9d2a1d2381cafdbe061f156ffff7f2004000000007571007e00020000005103000000f74053aa521c7ec2fff900f5c35e0e4a1a31baf0a033b6f27bb5cb1855da18581305f5c6a069254f26e6f7ac0c21362de91219a77b5352715a3c2783a604f22de061f156ffff7f2002000000007571007e000200000051030000009a6497084d6988cb3814727c4e0a6801eaff4d0f03153539eac382cdc4cb8e3a78f1449437b35987e303b98c39b861f5494ebcbf12a1a816bc0c32d1404999c7e061f156ffff7f2000000000007571007e0002000000510300000020ff15fa951e4e9e33572788f504cec9775a798957b5168d8363403bd8cd7e57f5abae367a6cc7ad2547a4c9f12472bfac75fc66fbc2e60f2afafb243491bf7de061f156ffff7f2002000000007571007e00020000005103000000499ff6cfd1abeb217f658234361f8cbe19688bb8dc6e8d64b384988f687de41a1cfb7499085ac2c629ff56bb68fc9f90d025d4c426bc1b08c11b671ff4ee59a8e161f156ffff7f2001000000007571007e000200000051030000007dddf1a54e0600e7262f1a15e1f9cd58f331542ba528f668a73391ae798c2461dda896d045f6efdc31e1ed67f182dc8881aaac1ba1e18f28ae0a252cfcee7aafe161f156ffff7f2001000000007571007e00020000005103000000df48839490bcabbde7cacc3c8cad0a80538cad0ea48342966c7930abafd1ee5b09c30d062c010fcddf3b11cc6c019ee229b9ee4d19bd6dd5dd5b4ece4cbebf5de161f156ffff7f2002000000007571007e000200000051030000004e9e81a27563b3a0f2e7aaebed53dd393ec81214b67c86cd65dea4188e0c79736d271cdfa762c29263fd034e001858e60c9eb4162328869274f6b0493921507be161f156ffff7f2004000000007571007e0002000000510300000082f5e35adf950e7d72f1347660972b95867cf757db868e3d62487c3f6e6ffc0cd3e193f8e3681b0577af04393d6bdc5c4abf6a5b325c189190a6ce513b749432e161f156ffff7f2000000000007571007e0002000000510300000009aa31d5253b2353b2986934a14676c87bdc54617eb0bf2e733208b16440f05561ff94701a6e55d3f6c6a74a9b18bf957e93e6652338ad6a172c2e9bf5d725bbe161f156ffff7f2001000000007571007e000200000051030000009550f93a24576c8db9ceee21f31c898ced72d64c626e43f3f8748179f264fd77a8b0cfd51d8dff22ad7e98724c8a30ed1099fe0a443f80986fd594980a9a67b4e261f156ffff7f2002000000007571007e0002000000510300000059a3fc8036ce344e6344c93b6c9d3b52078490564658c502477a9bc144c8f42a48737f902adba445e509bfc5f7970168257ea1ba40e5fd5a2ccf3f279ab75e24e261f156ffff7f2001000000007571007e000200000051030000008a918d33254e1bad3fef745b3e5c1dc59971d92be7933338907b894776e9256ac91ab95ec296c588ad4e7c969aca147c898ff99b411698099f56e492153550aae261f156ffff7f2006000000007571007e0002000000510300000012ae7c892dbb7871eeb7ac8436fefe74dec759b47ad57bb5fa15dd238db39f3cff7685a55a32d59853ee7cd8dc7dc7ac2089d8e2608d101eb1b8c3536a61d943e261f156ffff7f2004000000007571007e00020000005103000000537e792abc91a2622ff9269b8503601e6eb59f595d21567d1e78a9cb3fb2f15603d891498d8d0b6c4e046ee87e139c064a6058d95fc10f3eb6e1cca3c72677fde261f156ffff7f2001000000007571007e0002000000510300000065a356b239da9b14e3837f06b3f993c1ed4f4ad2ba082ca5abdd0760c9cd6843905ea697327d8c1ef16cf0b7bdf1cf1fd0991fb07638a9a3d0a5c41b439c56aae261f156ffff7f2002000000007571007e000200000051030000006e947952cf95fbe9fb407f145c74f4fb113970da5b03c0cb4fcb4d8ba3287c63f028330abd27c125e3ac6783f979eea5fc088ce2d45f9b6c30b6f560d30703cde361f156ffff7f2000000000007571007e00020000005103000000fbd63a784d07c5d7b3725af1d2c95a80d55e716a6aa9746b22004488f85aa16c137e4076fdd366d56efc72c0ef6ebc7b5f0c87195909aa41a932860e2403e941e361f156ffff7f2000000000007571007e00020000005103000000e6148cf3f959c8480218ffa182bdb41203b4fb1ae5c2ea1d8db7b6db83542a05743ce32c2bb4136320d24dc05e8f3203144e716d77997764d9df10dc41c1cfade361f156ffff7f2000000000007571007e0002000000510300000055e6abb166ebb3ff836362bae8cdaa493e41de2926e0c0f4ddc0525359645672fe6282d7b2254336996a1a6861c4462edf10fbfd900eacba49128d000d53713fe361f156ffff7f2002000000007571007e00020000005103000000fca0a0cd7101ce3d4cf024f12ede0865031ad0cd7ff259822ca2c5a89f6bbd1a6472fb3b326af801dfdadbd4b3faa9081cb8c87192792ab8441cea38b52e45a7e361f156ffff7f2001000000007571007e000200000051030000000a09729ef460d01df49e5ed02b0d895dadc27a6f9dc48abcb22fc8f93f6d065f29af51c14cc91ed1e2aab02a00cbae329507dd6222aadc0c5bf98afee444ad10e361f156ffff7f2001000000007571007e00020000005103000000d72c1616e441621d1b033d3cf3bee418bf53ca95c748046332f1c1066468733a552ed2706b5dc6cd092e48406ef36c461020e5298eb7eb88657315f0ea826c3de461f156ffff7f2000000000007571007e00020000005103000000d9fd4ba07c1c46e50f3a8754855ef8c0bacddc5555c1f7f1a6c4e4749ce16805c2d82c1301f7d98fc71da2014d44d215615e5f0108460b84f90d282856247208e461f156ffff7f2002000000007571007e000200000051030000007b176cd8fa776873688fd77ab1b5ff2511e3eb6a4733ce3efde7a6a1516bf652c57da78b08c2ea44f9ead26367c8729b82ede70efd8df57c4fe26afc57f2e244e461f156ffff7f2005000000007571007e00020000005103000000304667770d66c8d6a4b5aefe2e735b1437e707246175fe5417ab698f9a10554ce3bd2642d0c3f135313dedae7c5c885760cc9eeda2863b857030bbc8bc1794eae461f156ffff7f2001000000007571007e000200000051030000005fb561a019048bf4b861b96a53df277e235f21816f81c989e6328309af7e4726e797339faac58c68627bed49a0acff8bc8e3900761b55c55c7a405445f03af19e461f156ffff7f2003000000007571007e0002000000510300000034de21b0204501d542ed6270334fa6478a6c52759e4494a0f22868ab45feb039ad3d235291a63d2285b4373ef371186aff632bfaad93a84577f77fdbd7168bdbe461f156ffff7f2000000000007571007e00020000005103000000e5a112e18a5270ef2ab65305ca2035b8fa3b35f948364b4b5ec223edaedcd87fc7a8a873e6e34c24b560651df0fde28b8badc321eec36476fad0f3f5681b5ad3e561f156ffff7f2000000000007571007e00020000005103000000b708351ea845c4212c4892631de775d36b9edce023eac0a656a1990ab088063ff74025ff3d09be4c0acc1f87dd5979d4dd06c3fbec241df8b1b47a2f08ae991be561f156ffff7f2001000000007571007e000200000051030000005a3342e784e5d118edd12ad644b71f8d586890db7fe26c2495e1fa0a60e4556824ea3ec1863ba4626bf72745dd8cbf6417bb5117bd2c348b7ebbcbc5516a6a19e561f156ffff7f2005000000007571007e000200000051030000006b1cecda8a153dffc4e031fc9cc09f3ff116e43d3e4d2714a7f98500d14e6470e39b951e079aca2179a8a3fbe2553811ecfce8ace08ffa0ce1d8cc68222ce9a9e561f156ffff7f2002000000007571007e000200000051030000003bb53b08f1e02926176e47b0b82524bbd8472a3ec3e137fa507e838ec42656254fe8b36fb917143c5686d007f21f96797f5a6789300d1630c6e172f40aa1c5ece561f156ffff7f2004000000007571007e000200000051030000005fb025afa71c1f084f25207210dfa12bfc02f57cc77d385e54b047d35f75133ac8765816ad790a8f784c62df6552ff77035302988bb7aafea1f790617ad70114e561f156ffff7f2006000000007571007e0002000000510300000040b2c8b1f6537c256b056d648085fa84865775e4fb49f8d26a028ca508b74a043aed50a11dd6dbacf315e37b9c7b126733ea351ecc58966dfb48c77a59c29e86e661f156ffff7f2000000000007571007e000200000051030000005d81354d52486a3634f25bc286fd473ff33ff88cc840d5b6dfa9b2071fedee168e7fa8265ad1638dbfc2c77ca5d6f2ead8166d7c4a025e2ea694ad1572147250e661f156ffff7f2000000000007571007e00020000005103000000e07b11c9f1c4ee438f71f1a583aa631e2af425b39379e9ba0c6d59ae4e0b115ff22d753efeed9be4519c3c0e2d00949805d4186577a3b52d32875f6e769c00f3e661f156ffff7f2006000000007571007e00020000005103000000568b4a6d22e36a407d7774094caeec64d18e44a704dc8f8c3b35bc30338aa26b070f7cff008b49723810ddd8fadf85def6f17c1e4ae0d7052c1a468819e0f94be661f156ffff7f2002000000007571007e0002000000510300000086f96a9316fafdd703db1b127eb621c50fcda91b788807c86c7ccfbd5b8320600fda433e903b010ee9ee7ed516dea0b08e58277b76206f5eaf6b087d7bb4f125e661f156ffff7f2000000000007571007e00020000005103000000b3abeed9b722b06977e913375e3e1a6f6ac444a6cfb8a6fef3c918e873674a5075d38a7676926e2f8f9d0c4a4e5c7e71555ec639f34fe4c3e592b14f1bc47686e661f156ffff7f2008000000007571007e00020000005103000000eb1393cde15d8484c13228b0f2074f3b9318f38b79b912921a94bf2fe0654902ddab4ccb565c66431a213211012330475ee6288d866a81c803e178d0b770aac6e761f156ffff7f2005000000007571007e00020000005103000000b00b710d9c28e3802aab2d2029837241437ec98690268f91678d62e5ff7360659ef6731b139a52ac943e1c2b5173784db7a46503eaebd7906feefc61d7520551e761f156ffff7f2003000000007571007e00020000005103000000761ae088dbda65e374f03ce188441648e6ddf5bf521853272b6dd2b8940de41debd7115448add3c77cbef63ecbf2a2e0f534d126341963b7e392f4ada6d34e78e761f156ffff7f2001000000007571007e000200000051030000008e3009bf1dff400ab9b710cf1a9159d0a65c3fc59737752017b0180b72210d5dca1f644c8b212c6897bcb5680168aa4d04b123dc0f5e606e766f95ef895e7bcce761f156ffff7f2002000000007571007e0002000000510300000044156b03372bd0b65d940cdf4387d78b90ffaa420dc3ac4aa6bc4eb11aacfa1839c73938e8766c2c5fccc792f05c925e236d9236f8b358fdacca541f9ccf5297e761f156ffff7f2000000000007571007e0002000000510300000090bca204d9fe7948f1c3d05cafab46a346b41df6a8fed257cb07d78ea4c86e086f630cbede7e445ed541fe7048cf03ec32cbe6975a004140291fd918ba6dd801e761f156ffff7f2001000000007571007e0002000000510300000082ff40eb873ad2f73b3d8a5a341fc97b336bb165ee42ad92bdcf24680e2e611d7a5f09d0f873b016f42cbea077d0b8c76ba7209ce9d1761ffaa0f5e9f3e83c32e861f156ffff7f2000000000007571007e0002000000510300000006cd890114e6afb7664962eb557d3b06b2ea997a514af46cd474c562191c7b632f5067e4223ad99c7952ae623d3285c4c7ef4fef256ae386f58ab4829d9d382fe861f156ffff7f2003000000007571007e00020000005103000000df9659dddd830a4b458ff2d053f5ee55f461a34dbd1e07706db763c9946d3b739ce84824ca498908063a616c06016e3c5137b422bdc836404dadfa60713c8e05e861f156ffff7f2002000000007571007e000200000051030000004d60ec68808ce033cc2b23d70ee753bacba3fc9d366584c6be810b019fa56c270493ced263ad7974ddd1f961e8e05009729daf8d2f7287f8d413e55891a0f75ae861f156ffff7f2000000000007571007e00020000005103000000dff561e83d341dbde176c007e7da8500b096356254abd88689ac894ce628137c3c2e3741ed601b65ea082e6655076338627d0c750471becd17a673333145834ce861f156ffff7f2001000000007571007e000200000051030000005bc3381953f40f765eef3f0e36fca112af73563723e76963415face52e34e06c42957295a7af51dab6e524d298833e21fe41f7f602a15ca1b1a9d616c0f6a048e861f156ffff7f2000000000007571007e000200000051030000008041b266a802e82cebe4e5e92a3e828caf056a297a9926f915ac26babf486a64bf4b0ec27039b2f548469f9f6a226a77ff3aecc1fc664ef630e640c380bcdea1e961f156ffff7f2002000000007571007e0002000000510300000025c43f1447e5886c2743e8b54edbe146b423531e1eb8996a9e72cc8c6d26a73aaf282bb3c1562d95334c2f85a4764024f1081f7d827fc9dde72369bc31693963e961f156ffff7f2001000000007571007e000200000051030000000c2ea9785ddbc524947ae602c99e15a6233c97c72a0517362f93709383ab1b11118610a5e768b89edbfd40ab692c85c16cbed5d63a34186872acaa2f8a67045ae961f156ffff7f2001000000007571007e0002000000510300000098d38627e356911347e96df403897ffe3266ac4e05cee2a6c51bc5a041cc9048ab07533834c02f20b71b648d9ff0674fb5dfa6ccfa4ec211419250c34764b5e0e961f156ffff7f2007000000007571007e000200000051030000006281342c44bbb94aed4e4a49858e126b8e8e137ec7fc291963c120d4b15824355f9df19fff0c8f5aae9d774becf0e00bd7c62fcdf3e293fc73919e451e5c5fcbe961f156ffff7f2001000000007571007e0002000000510300000055e6a6a917a51c0024f5f0cbdb300f4976c0033636cb5d5fe8d32a250d3a4906b3aad51a1da461aaaf84b798af59cde5850ff673fd64cae7c0f9c2eea7a9dadbe961f156ffff7f2002000000007571007e00020000005103000000a5d6d2a3ee06bd5abce7755ad8e50a6da5ac0f59c80b5da69995fd4f30534714eb54a3a235615db22626d08c87323e0d69a2a478d3559c1264a1c7914ff66c37ea61f156ffff7f2001000000007571007e00020000005103000000eb8ebf5bfbad9a0a994fbfeb8aeeafa5a97199fe454d2ddadaf50e011fa1c55eb3029802e551efccedbcf6e90e5b30d8dd32d3b05e21d4d4b28fa851c9dedf30ea61f156ffff7f2000000000007571007e000200000051030000006b28d46ee2b0cd3f93166f33cbdcb5be3989ffc1e0e58b46b2f620f559d44738544abfe2d374a9679ba1e100ac15b4e42e161971214dd0ec3beb840f8e3105e8ea61f156ffff7f2001000000007571007e00020000005103000000985301c59dc4179e172769b4fde711d8d89266d0a7c0b914f6cc08a2880a4b7c097904724f511ae5aabed07be334606fce2cb2e9e4499c16ee3ce801aa6d4e76ea61f156ffff7f2001000000007571007e00020000005103000000111f5e998c7d84bd30a215fa527eebe3cfc55782da70ed17d45facb01d54807c08aaf345e92fea415c4642c7f63c961d21a04674f8e9ae639d37d17893c221f9d87ff156ffff7f2001000000007571007e0002000000510300000071b79b43c66ba9580ee3a7416d25702045faf570ea6911ddda3d2812fe139a04aa3328a6575bac6f520eb83e07c0a74a343563de6e5bd77f25cf5cfa1978e3f2d87ff156ffff7f2000000000007571007e00020000005103000000b0ab92dc709cc888e00fab1a55deab04a935802085e6a665b116dcc322353825a18e77b1b63489d7d2f00a1778548dc253befcf518dd03638c4fa35e69ba3a78d87ff156ffff7f2002000000007571007e00020000005103000000d3191298c19fcb1973c57500cd2c4c0bee18d764d7f84494b878199de357d77bd8d99e5d8d92fc4db825aa41c8ffbf69f6ee551fce9a2024e236c7539f5bfc31d87ff156ffff7f2002000000007571007e00020000005103000000bfbe704bfb4b6f0a417d5900886a11a82bdb984fb000fa1f058b103dea4d5f423ded8c53ab47c046aba8b209a9501c7aee8e618a98c5aec84f645857066985d8d87ff156ffff7f2001000000007571007e00020000005103000000575ede1a75e4fa752b95d593d8f1a6112039700650a04e8115c307806e313814c08ebb3661408aad199851d9418685102dc5a4d57a6a3e9b1c5e4950dc00df85d87ff156ffff7f2000000000007571007e000200000051030000004f32d3abd77e00e6c722b451b22eceba3399c2794b088d0e9c30a3ce5ca3c704875bfdf013be196f7d4d9c818891cd77a119d9ffc8b18d2d6c4054db2dfc651411aff156ffff7f2002000000007571007e00020000005103000000ac40022e3775ebd3201ee9bd206ca0f30cad0712a16407069217d9597702e578bc4563a6745a9a07474c4c353df7bbd587857a777bf56051b2363ab015d15ef132c6f156ffff7f2002000000007571007e000200000050030000002fec6bddef1def9d64807571048a1c3d1b6ee2f31a626345375dc2a4b17c2374e4063a2efb1c4772df46ec7a5f234d89872e88c8a27a2ae2a061b245fe502fe58b77d857ffff7f2001000000";
        ByteArrayInputStream is = new ByteArrayInputStream(Hex.decode(headers));
        ObjectInputStream ois = new ObjectInputStream(is);
        Object[] headerArray = (Object[])ois.readObject();
        ois.close();

        long nonce = 0;
        long value = 0;
        BigInteger gasPrice = BigInteger.valueOf(0);
        BigInteger gasLimit = BigInteger.valueOf(1000000);
        Transaction rskTx = CallTransaction.createCallTransaction(config, nonce, gasPrice.longValue(),
                gasLimit.longValue(), PrecompiledContracts.BRIDGE_ADDR, value,
                Bridge.RECEIVE_HEADERS, new Object[]{headerArray});
        rskTx.sign(keyHoldingRSKs.getPrivKeyBytes());
        return rskTx;
    }

    private Transaction buildRegisterBtcTransactionTx() {
        String txSerializedEncoded = "0100000001d63a3ceef306c8ffa7cffe1d639491361bdc343f6a2727232771c433730807e7010000006a47304402200ec9f11f92e8d9ff4281685ed88e047a429956f469d2f5070ac754d1c75b18de022001904bf6701954b06871b40bff8709532d65d817073318ebdd0d2ecc032e12ae012103bb5b8063fc6eab12f86c28c865f3aa4337e46e9b8cadad53b167be3682f3e4d2feffffff020084d7170000000017a914896ed9f3446d51b5510f7f0b6ef81b2bde55140e87a041c323000000001976a91473fd38bb63a8fece454677e38e2b8590f8d8b94f88ac5b000000";
        byte[] txSerialized = Hex.decode(txSerializedEncoded);
        int blockHeight = 102;
        String pmtSerializedEncoded = "030000000279e7c0da739df8a00f12c0bff55e5438f530aa5859ff9874258cd7bad3fe709746aff897e6a851faa80120d6ae99db30883699ac0428fc7192d6c3fec0ca6409010d";
        byte[] pmtSerialized = Hex.decode(pmtSerializedEncoded);

        long nonce = 1;
        long value = 0;
        BigInteger gasPrice = BigInteger.valueOf(0);
        BigInteger gasLimit = BigInteger.valueOf(100000);
        Transaction rskTx = CallTransaction.createCallTransaction(config, nonce, gasPrice.longValue(),
                gasLimit.longValue(), PrecompiledContracts.BRIDGE_ADDR, value,
                Bridge.REGISTER_BTC_TRANSACTION, txSerialized, blockHeight, pmtSerialized);
        rskTx.sign(keyHoldingRSKs.getPrivKeyBytes());
        return rskTx;

    }


    private Transaction buildReleaseTx() throws AddressFormatException {
        String btcAddressString = "MZX2Br6zgQ2TVCjQngLN3x7abQNMbFtdeG";
        Address btcAddress = Address.fromBase58(RegTestParams.get(), btcAddressString);
        long nonce = 2;
        long value = 1000000000000000000l;
        BigInteger gasPrice = BigInteger.valueOf(0);
        BigInteger gasLimit = BigInteger.valueOf(100000);
        Transaction rskTx = CallTransaction.createCallTransaction(config, nonce, gasPrice.longValue(),
                gasLimit.longValue(), PrecompiledContracts.BRIDGE_ADDR, value,
                Bridge.RELEASE_BTC);
        rskTx.sign(keyHoldingRSKs.getPrivKeyBytes());
        return rskTx;
    }

    private Transaction buildUpdateCollectionsTx() {
        long nonce = 0;
        long value = 0;
        BigInteger gasPrice = BigInteger.valueOf(0);
        BigInteger gasLimit = BigInteger.valueOf(100000);
        Transaction rskTx = CallTransaction.createCallTransaction(config, nonce, gasPrice.longValue(),
                gasLimit.longValue(), PrecompiledContracts.BRIDGE_ADDR, value,
                Bridge.UPDATE_COLLECTIONS);
        rskTx.sign(new ECKey().getPrivKeyBytes());
        return rskTx;
    }


    private void assertReleaseTransactionState(ReleaseTransactionState state) throws IOException, ClassNotFoundException {
        BridgeState stateForDebugging = callGetStateForDebuggingTx();
        if (ReleaseTransactionState.WAITING_FOR_SELECTION.equals(state)) {
            Assert.assertEquals(1, stateForDebugging.getReleaseRequestQueue().getEntries().size());
            Assert.assertEquals(0, stateForDebugging.getReleaseTransactionSet().getEntries().size());
            Assert.assertEquals(0, stateForDebugging.getRskTxsWaitingForSignatures().size());
        } else if (ReleaseTransactionState.WAITING_FOR_SIGNATURES.equals(state)) {
            Assert.assertEquals(0, stateForDebugging.getReleaseRequestQueue().getEntries().size());
            Assert.assertEquals(0, stateForDebugging.getReleaseTransactionSet().getEntries().size());
            Assert.assertEquals(1, stateForDebugging.getRskTxsWaitingForSignatures().size());
        } else if (ReleaseTransactionState.WAITING_FOR_CONFIRMATIONS.equals(state)) {
            Assert.assertEquals(0, stateForDebugging.getReleaseRequestQueue().getEntries().size());
            Assert.assertEquals(1, stateForDebugging.getReleaseTransactionSet().getEntries().size());
            Assert.assertEquals(0, stateForDebugging.getRskTxsWaitingForSignatures().size());
        } else if (ReleaseTransactionState.NO_TX.equals(state)) {
            Assert.assertEquals(0, stateForDebugging.getReleaseRequestQueue().getEntries().size());
            Assert.assertEquals(0, stateForDebugging.getReleaseTransactionSet().getEntries().size());
            Assert.assertEquals(0, stateForDebugging.getRskTxsWaitingForSignatures().size());
        }
    }

    private enum ReleaseTransactionState {
        NO_TX, WAITING_FOR_SIGNATURES, WAITING_FOR_SELECTION, WAITING_FOR_CONFIRMATIONS
    }

    private BridgeState callGetStateForDebuggingTx() throws IOException, ClassNotFoundException {
        Transaction rskTx = CallTransaction.createRawTransaction(config, 0,
                Long.MAX_VALUE,
                Long.MAX_VALUE,
                PrecompiledContracts.BRIDGE_ADDR,
                0,
                Bridge.GET_STATE_FOR_DEBUGGING.encode(new Object[]{}));
        rskTx.sign(new byte[32]);

        TransactionExecutor executor = new TransactionExecutor(config, rskTx, 0, blockChain.getBestBlock().getCoinbase(), repository,
                        blockChain.getBlockStore(), null, new ProgramInvokeFactoryImpl(), blockChain.getBestBlock())
                .setLocalCall(true);

        executor.init();
        executor.execute();
        executor.go();
        executor.finalization();

        ProgramResult res = executor.getResult();

        Object[] result = Bridge.GET_STATE_FOR_DEBUGGING.decodeResult(res.getHReturn());

        return BridgeState.create(config.getBlockchainConfig().getCommonConstants().getBridgeConstants(), (byte[])result[0]);
    }




}
