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
        String headers = "aced0005757200135b4c6a6176612e6c616e672e4f626a6563743b90ce589f1073296c02000078700000006e757200025b42acf317f8060854e00200007870000000510300000019ef1d046a4495ed2b8981019d789351be13f6605381a265f143fbbd43c2cd4acb7ce5d0da9e660ea5e3032e281ac765ff4b13a944da0140b9bdca24e24f5090d861f156ffff7f2000000000007571007e00020000005103000000878f81de91779afbc9958a1b2cc648139ee2998a6eddffb37511ebf995f95234b5a2bccd28736bdfed3692fc905a7f2b44c09af87f1c4f9d7b22105573689d22d961f156ffff7f2000000000007571007e0002000000510300000009099b0efe91d9cd15d024dd726e4e5eadb58a1945383ceb5580a222bcab66741f7ea963a0f22a270a3574c9ed734f45b5b6d466131e6e870dafcdba8dddf52bd961f156ffff7f2004000000007571007e000200000051030000005dfe4f142d8899f5d5a712f7c97d3bc08660777d4629cb213f7fbe9b74d33a49820e6fed0387d362c98439dd5d68ee4f6fd9bebe6c1b97f6c9136c4818baa689da61f156ffff7f2001000000007571007e00020000005103000000b2f7257137ab10bfa52aa64b68823daab908ab3ed242098cfa7484b9b430db35ba3b9c07d88687abae9fe34c73bddc14dfb55cba454a8c7c359b774a158400dada61f156ffff7f2002000000007571007e000200000051030000000ee835b5f3358abb7beb034d13b8689725318157fd637ae11d01a5fa6aa9bb4f19d74e636f3dac921a64ef794ccabe36374f609a3b8faab4368f30d50a7f5825da61f156ffff7f2001000000007571007e00020000005103000000da05b973bfc7ab5ce20000612780f8875dd470a72da73bd4ab02a600df469e5e4a42d428a651d88522419491e7b6f3a055440daa7096bc50ac841da3c56a0ff7da61f156ffff7f2001000000007571007e0002000000510300000098c9b1276d2140d19af9832d6455c79a531c0bb9f11e30f0bcf4f48f99d9bc4f7d0d854494d7316867e2acc8b83a373ab488798e013e21867a02542d0bc0d65cdb61f156ffff7f2002000000007571007e00020000005103000000058adb07fe9fd30c63066362a08a230670c0048ec917cfc8a66beb40139a5850065a91ceb292932dc89f1437f94f263b4dcc849653380c85e37983cbe9e2917bdb61f156ffff7f2001000000007571007e00020000005103000000740fe22cf0f8041656ff2e3ffc52f5cecfc7e8ae62ccebef3364b7309fa4d96cdba893c6d0404c477520022db6b1812385a587fff74a93b2cdd65792b985573ddb61f156ffff7f2002000000007571007e00020000005103000000d26da9cf52418e78a8d31160d86517648c940acd509373187a04e2b59f18ba27a498ac41bade08b809e096302be853c9271606aada49453629915960eac3e68edb61f156ffff7f2003000000007571007e0002000000510300000084abd822d7a47ea0b3074d1b5a7f6b0c8e729bc08ea86a96601dd5110369bb32a3aa8b85093f35afb74f6870e4bce8aa86301fb2e2b7b5cd308591c2e9c83450db61f156ffff7f2001000000007571007e00020000005103000000bdaccc6bc1e4b7bd9067509674da5d121f641e9036c8f6c3cc2a579e4fb5fe1eb35a0a5e570bc4b35dad26a3a3640194eb7545df266a2d9056b8bffc3c9f995ddb61f156ffff7f2000000000007571007e0002000000510300000075f40760ceebfb29d6275fd0c04f5d7ce7c2a30702aa4808baff0dae49c80c6fd03fb162dd64a151c087f3bdd03d53dcc48b4b7fdaf249414c488de53fdcc8a1dc61f156ffff7f2001000000007571007e0002000000510300000039a580a1f4d0d863abbde1a29330cb966d58ac05107c36b8779af1d7a4d2020a105692cb1b07fb6d12da26ad5ad340d3643f8b82501c0cace385de0e562c3b7bdc61f156ffff7f2005000000007571007e0002000000510300000030bf45b7f8c767eba0e9f35c2a00298fbe4716b7718f39ec5ad812316687f94bea9aed89fd9d764bf71c6b66e697c751da87317f184efae08fda79a73351dab5dc61f156ffff7f2004000000007571007e000200000051030000005e7bb0eb7baf95952002e383f479e2ebe959d077b09a9db34796a3359246832c16a39524f5ad49046fe45b29d56e832508ea3f8f51a6f5804c17d89d38949dc3dc61f156ffff7f2000000000007571007e000200000051030000008e914a7d960a3df5723103508c80b459f5543c371d9e44b3412b2f0511870e7a34319fc6586bc50c42cd938bb1f7c523a30df44d790b774d3f336384084216a5dc61f156ffff7f2000000000007571007e00020000005103000000df175b93074063d2dc188f39da21e7d17db09d4fdd056a89024d6b65ba39d00105709404718dd138c79cc0159a84a14388449bb657526d58159e07f7df02f2fbdc61f156ffff7f2004000000007571007e00020000005103000000b8059ea906894abea1f528ee42162e64db584065d1353c540c7d8bb731f782255893d4e79d714af298b231935cebb47e2ee480c74cded044966568bb529e8a95dd61f156ffff7f2005000000007571007e000200000051030000003cc78dafb677fff5c93a97c727ce4c06c53de37652d274af32fd18a8b6ea3810d218f0755c0fb782ab4d1e62903ddcf5c7c90335600587f1666f11fe4fb9b97edd61f156ffff7f2002000000007571007e000200000051030000009b8795850e6c6bb1e8416fe562b6f5197f05b2571c1a49e94f4aa8105ab65a02b3e7598636b66ccdd6650ab9f45a066d430f73572dabe033eba155a3b70d866cdd61f156ffff7f2004000000007571007e000200000051030000007881e3be682775f0029e7f16afb3ca850a8ecf6eddc82177859ef3dfc57a6d66176f58d1f847fceb6fcf7041fcb21c5d54ea5c1ed39aa1294f36c2a409cccd72dd61f156ffff7f2002000000007571007e00020000005103000000eafb5be04db90b61bd64c0b352ab1350380840f2bf3738a7ad9daed66bb70b1e11854ef542e4a234eef6ca89fa938934ba500d9cc79523b9944ed5975ef875c8dd61f156ffff7f2001000000007571007e0002000000510300000076e62e6a43525e1cb5d57608eb4110fa06a625b3e3ddd92e81a2e1743e02df748a7bfd2e664e4aaa31afce39f92e6624eea497be4e61b8f39da17c5aec0299b1dd61f156ffff7f2002000000007571007e000200000051030000002807ceb81c207bc068dc6aadc8d6b0522acdf71b7dd005e3ca79cfa12390b0422403b6c3ba9314597cc685f441e01f5ff8f767a8a3c68272c9300cf227506358de61f156ffff7f2004000000007571007e00020000005103000000ef11d3ac285b1e69eb6d7cf27d64984cec18b01269d5b3567c4d92eb978eda34f9892510cfd1aea4b1a8b7ebe3656e3ccfc6d08af0b3fbfdaeb13484fc25c241de61f156ffff7f2000000000007571007e0002000000510300000028497dd76db3c7c30db3cd8a5357fae165c47f9b571d6ace99fd9213a81a5361a60a25bee417bc21e6338f7361d51b5740c1973d849dafa97d29a13fe2cd6971de61f156ffff7f2006000000007571007e000200000051030000003081787f16ae69cb836199358b51a8c5995df2e5b7c45a550f2db6aaf9a59a1c1f38e42f64fd67a4a7865f2f138a6a0389d2d4aefab72ee9221a3f5241746930de61f156ffff7f2001000000007571007e00020000005103000000b364753b331bec8afa9aea6c3ac20517d9ac5fef6e918a243fd34a4e47bbee71f862f539a796e91f5606225b18b508d4f469fde68158a0a62e84bc1753ded511de61f156ffff7f2000000000007571007e00020000005103000000f656bac195087dd5b2ca245bbb42a2b1e136b1499026dc5160cf5676103df451e8d5822aa8cb4cf6ee46ac86ed484ca0f870bdc7acce26e1b69df877a66bb31fde61f156ffff7f2002000000007571007e0002000000510300000091bef9e9a9b4db026178c3c670e786a69a966dfaef18450f9bce0537a39f336e2b8a1e61bee737988fa1c9dbd4857ed54c6ebfbbe242dc937590613e63643228df61f156ffff7f2001000000007571007e000200000051030000007a725c274a914ba67f53a7bc306e74abc0edfc4baa79d3ed14504ca53083595ab09ceca193791a6b622d10a1f04ecb34764feb90420433013148d63a80670e0adf61f156ffff7f2003000000007571007e00020000005103000000c6815245dd67987681969895475b36e846dd2e87fd40a87c66259d411e2f4f70e5e9bdb2d123671965f1d447cb47d4f87f6a3729f8d4dd2cdd756f9924927bc0df61f156ffff7f2002000000007571007e00020000005103000000c321d86b64df882f5ab0ddf5769d1a8f100ebf1fd56ff6c6598f2341ff7b5706dd4aae07274044d6a781a141ff286742dd098228f8cc5fe4265ddac2665e7801df61f156ffff7f2001000000007571007e000200000051030000002dee10f20ba504428dd6ffa3fb7973c69841e5e5aee1da1d0c7cccbd3e3eb14b623b7ba0b501dedc3ed46bacebbe171d7cc49d91484a0c86f5da30e3ec781652df61f156ffff7f2001000000007571007e00020000005103000000f37bde5b25eebc339441862c79957907e68b537f9635571209c34e359101733ea95585518a4ac5c6f4e3ec94ba81cf1299c1060e5ab85466ce3ddca4c0bdac6cdf61f156ffff7f2003000000007571007e000200000051030000006bdf9bbe0db626b6bd5963662538b6877e9dd3c74b02f9ca0825953b076c742a71ae4174c9ab87f6cb51f1f6fa20fe441fe9d7969e1971d57cc74e6afb5792a6e061f156ffff7f2000000000007571007e00020000005103000000646afdafa5dd606e7b55896c6674e1cf5acf3eb2767faddefab5188c73528a04b2075f586656e4aa190115d8447685df88d778b0af7bcc2e29907e812dd41495e061f156ffff7f2002000000007571007e000200000051030000009146be4dac05a74f59f57599e1ca6c7987bd5ef93ecccc05e560f8e86e38780199b7aea464577f38dcfec08d7e6989ee3473111b61c4289ae9d2a1d2381cafdbe061f156ffff7f2001000000007571007e0002000000510300000051492b4e1e3ab8b235f5b6e5e5a090ded406a723d350910191cb2d260205773d1305f5c6a069254f26e6f7ac0c21362de91219a77b5352715a3c2783a604f22de061f156ffff7f2001000000007571007e00020000005103000000897a7a3db92a9854a1bb9ba80e2cccf6fd785108564c44561c235f3917dbab3578f1449437b35987e303b98c39b861f5494ebcbf12a1a816bc0c32d1404999c7e061f156ffff7f2001000000007571007e000200000051030000006c77dfff79ca1e62c5eda7c7eff2905e50d7489b7bca6fd34dc0f3bd63ae9401f5abae367a6cc7ad2547a4c9f12472bfac75fc66fbc2e60f2afafb243491bf7de061f156ffff7f2000000000007571007e000200000051030000008e8a8b92bcda19c778ffc39e60d3cf3a7bd0b27723711340fd82cdf6ca7844541cfb7499085ac2c629ff56bb68fc9f90d025d4c426bc1b08c11b671ff4ee59a8e161f156ffff7f2000000000007571007e000200000051030000006551a6f4646762313aacca05e6206ca25d2ccc89e6f9795b194bbfb5f04ab76bdda896d045f6efdc31e1ed67f182dc8881aaac1ba1e18f28ae0a252cfcee7aafe161f156ffff7f2000000000007571007e000200000051030000008933e12edf995c5360a3a372772baf5918e1ad0062755311683c009876bc955b09c30d062c010fcddf3b11cc6c019ee229b9ee4d19bd6dd5dd5b4ece4cbebf5de161f156ffff7f2003000000007571007e000200000051030000005dbac18c12f5806a6bc6eccb556d6cf732f50ef944d4e71c559e6289ed0ba2096d271cdfa762c29263fd034e001858e60c9eb4162328869274f6b0493921507be161f156ffff7f2003000000007571007e000200000051030000006abbb08062c3098d236c56db26848868f5d671ac96e217ac570ae2d6d765af53d3e193f8e3681b0577af04393d6bdc5c4abf6a5b325c189190a6ce513b749432e161f156ffff7f2000000000007571007e00020000005103000000c90b16b1d6e934de125f962d09364aa987991f9359c2d9e0f070cf2ec0cc1f2161ff94701a6e55d3f6c6a74a9b18bf957e93e6652338ad6a172c2e9bf5d725bbe161f156ffff7f2001000000007571007e00020000005103000000209a5504af72e5112c2847ee3a46b1767deab0b9b9cffc98c7dae957ac26d809a8b0cfd51d8dff22ad7e98724c8a30ed1099fe0a443f80986fd594980a9a67b4e261f156ffff7f2003000000007571007e00020000005103000000a1ebf618bb297afb0a27566fafb4a8d5cade4b1719bb3f7fa236d92d74fd801348737f902adba445e509bfc5f7970168257ea1ba40e5fd5a2ccf3f279ab75e24e261f156ffff7f2001000000007571007e00020000005103000000ff9c7c484cd2e5323bedadfbb92c4a27931dc0521ba8f5d3d49768670f7fbe45c91ab95ec296c588ad4e7c969aca147c898ff99b411698099f56e492153550aae261f156ffff7f2005000000007571007e000200000051030000003d1165457f01fe9f0182af142de7d3382e7d68c4533d2baace6c182a45ce3944ff7685a55a32d59853ee7cd8dc7dc7ac2089d8e2608d101eb1b8c3536a61d943e261f156ffff7f2000000000007571007e0002000000510300000062031a877f481bae34bf0ca7e075fa87371eaefc2ed51b81fd3806c43774046e03d891498d8d0b6c4e046ee87e139c064a6058d95fc10f3eb6e1cca3c72677fde261f156ffff7f2002000000007571007e000200000051030000001385c8673a13ec5f32ea9c7104f992d30a2a411edb5658a4c9a3f1b76b70532d905ea697327d8c1ef16cf0b7bdf1cf1fd0991fb07638a9a3d0a5c41b439c56aae261f156ffff7f2001000000007571007e0002000000510300000079044dbf940f5337d93d861b837f093d7bcff07486169df51d21b58f67dc1619f028330abd27c125e3ac6783f979eea5fc088ce2d45f9b6c30b6f560d30703cde361f156ffff7f2001000000007571007e000200000051030000003bcd7eecbb432f02f45b18847a29dbf6a5c57db31beb329014280dd98fefcf25137e4076fdd366d56efc72c0ef6ebc7b5f0c87195909aa41a932860e2403e941e361f156ffff7f2002000000007571007e00020000005103000000479617d29c097f33f56dcb915334790c5a9906bf602cbacddd5eed3b244fa13d743ce32c2bb4136320d24dc05e8f3203144e716d77997764d9df10dc41c1cfade361f156ffff7f2001000000007571007e00020000005103000000ad77aa219683cdae17d7fc1b7ed9f8576d96c10cad7bd00570128f0a8604a517fe6282d7b2254336996a1a6861c4462edf10fbfd900eacba49128d000d53713fe361f156ffff7f2001000000007571007e00020000005103000000102ac6843ebdbed01c9ea3dd7f44e74224a2f2e573310ad116c1a8cef0aed6616472fb3b326af801dfdadbd4b3faa9081cb8c87192792ab8441cea38b52e45a7e361f156ffff7f2002000000007571007e0002000000510300000007c250dd1e8264475763df0ebfdd5c88713ec0c884c762edbb57638a77f7a37229af51c14cc91ed1e2aab02a00cbae329507dd6222aadc0c5bf98afee444ad10e361f156ffff7f2000000000007571007e000200000051030000008e1f6f6b167b5f618c1bd13b4316c80ad69f82a05dda9723b52fcc543e1dbb7d552ed2706b5dc6cd092e48406ef36c461020e5298eb7eb88657315f0ea826c3de461f156ffff7f2001000000007571007e00020000005103000000d47c3ba2645b27ff6218e7e209a8eabce771efb8b5f3608dc6af20b31c318949c2d82c1301f7d98fc71da2014d44d215615e5f0108460b84f90d282856247208e461f156ffff7f2003000000007571007e000200000051030000001a5e27972883383d7a2303471bb5fcf19aa5f8746b76a9d0c2b49f20f697df4cc57da78b08c2ea44f9ead26367c8729b82ede70efd8df57c4fe26afc57f2e244e461f156ffff7f2004000000007571007e00020000005103000000e05bcc63cd1a502d5c2604892bd4bb2fbaf0d5dd125efc02aaa59be8ee36fe79e3bd2642d0c3f135313dedae7c5c885760cc9eeda2863b857030bbc8bc1794eae461f156ffff7f2001000000007571007e00020000005103000000dc21275f160aeb0fe34e63e6512d87c4663292f179e93fdd5cb1c8a6d34a9b1ce797339faac58c68627bed49a0acff8bc8e3900761b55c55c7a405445f03af19e461f156ffff7f2000000000007571007e0002000000510300000087810aa26b45c5de8da38a126257f73205d9abf86d52e2fcd400a59925c53142ad3d235291a63d2285b4373ef371186aff632bfaad93a84577f77fdbd7168bdbe461f156ffff7f2000000000007571007e00020000005103000000f55a6d2896ff63a9095be517659a9ac71c4b9efbb847fa885943761d47236008c7a8a873e6e34c24b560651df0fde28b8badc321eec36476fad0f3f5681b5ad3e561f156ffff7f2003000000007571007e00020000005103000000c9ec55af700c7fcc2004dd28c0868520e11088572afb2e88d93fcede6966b06ef74025ff3d09be4c0acc1f87dd5979d4dd06c3fbec241df8b1b47a2f08ae991be561f156ffff7f2001000000007571007e0002000000510300000034a8daf4ed654d34b74d58a0ac4400d0a9181bc3e80264826231a0e2be869a4124ea3ec1863ba4626bf72745dd8cbf6417bb5117bd2c348b7ebbcbc5516a6a19e561f156ffff7f2004000000007571007e00020000005103000000dc4930293c24f9ee311cb9c9ccee0bb6a2cd95670568c6c1d062aea3135dde5be39b951e079aca2179a8a3fbe2553811ecfce8ace08ffa0ce1d8cc68222ce9a9e561f156ffff7f2000000000007571007e0002000000510300000069c3520e1dd6eae68ab07af41f33b2435813992dd3069caa1ec93325e3a93a404fe8b36fb917143c5686d007f21f96797f5a6789300d1630c6e172f40aa1c5ece561f156ffff7f2006000000007571007e0002000000510300000001c3bd5f3fa11c77400c3799a09a16b67fc5a2d0951c9dfaf6a8450aa6a5cf10c8765816ad790a8f784c62df6552ff77035302988bb7aafea1f790617ad70114e561f156ffff7f2004000000007571007e0002000000510300000019576a111408d4d21409dfabdb5a2bb683371b7b632b3eaa3125ae5f4adef26d3aed50a11dd6dbacf315e37b9c7b126733ea351ecc58966dfb48c77a59c29e86e661f156ffff7f2000000000007571007e000200000051030000004037c85a40f0f3bd552c9f51a39385debb3eae43640f584a2401202e38c8ad418e7fa8265ad1638dbfc2c77ca5d6f2ead8166d7c4a025e2ea694ad1572147250e661f156ffff7f2001000000007571007e00020000005103000000dfa25c4701fbcbff9c3b6ad921169be595785c9dab8c1839b9230cf3c750b935f22d753efeed9be4519c3c0e2d00949805d4186577a3b52d32875f6e769c00f3e661f156ffff7f2006000000007571007e0002000000510300000075dcf5f9708bdcba34505cf69f827e940d8836b882eec0be02651aa3b833d852070f7cff008b49723810ddd8fadf85def6f17c1e4ae0d7052c1a468819e0f94be661f156ffff7f2003000000007571007e00020000005103000000a655d9ff3145ff74c29c6bbad8f0f92886c257ed1bfb72a3e1e8c73bf9286e580fda433e903b010ee9ee7ed516dea0b08e58277b76206f5eaf6b087d7bb4f125e661f156ffff7f2002000000007571007e0002000000510300000088b2f80d16b05d156b661dcbaff95efc06f6c50d0e0efcc20e12abfbe78f520575d38a7676926e2f8f9d0c4a4e5c7e71555ec639f34fe4c3e592b14f1bc47686e661f156ffff7f2009000000007571007e0002000000510300000053ec838c2575248e8e524368d9213c86d2c9c9e9169c62916119fdfe801b2563ddab4ccb565c66431a213211012330475ee6288d866a81c803e178d0b770aac6e761f156ffff7f2006000000007571007e0002000000510300000005be5ab03641f0df0168860556ea442c49c9b1ab377e9cb65a60e1e5ee31a8239ef6731b139a52ac943e1c2b5173784db7a46503eaebd7906feefc61d7520551e761f156ffff7f2000000000007571007e00020000005103000000cc493b05bede1949ca45c80b6adbf820313620293b7f6f24d8cc1fa041c6103febd7115448add3c77cbef63ecbf2a2e0f534d126341963b7e392f4ada6d34e78e761f156ffff7f2002000000007571007e0002000000510300000028968c4a7322000fdc33f552edad2904ba5c6bc97a232206d49f20a86e67f875ca1f644c8b212c6897bcb5680168aa4d04b123dc0f5e606e766f95ef895e7bcce761f156ffff7f2000000000007571007e000200000051030000006fb06baa5e676bcd0db484e31867e8cea45b5942324dae9eea015372f294be1a39c73938e8766c2c5fccc792f05c925e236d9236f8b358fdacca541f9ccf5297e761f156ffff7f2003000000007571007e00020000005103000000a1eedc11d1691c52fcf7055d998e82fe21c9a48b7579b59b94423767ce8cb91e6f630cbede7e445ed541fe7048cf03ec32cbe6975a004140291fd918ba6dd801e761f156ffff7f2000000000007571007e000200000051030000008c057e4604f3bfb8b3f0e85ab8409927b50d38af69f2a09f49d7c1f5835c673b7a5f09d0f873b016f42cbea077d0b8c76ba7209ce9d1761ffaa0f5e9f3e83c32e861f156ffff7f2000000000007571007e0002000000510300000092f89e596633dcdb2c54b6f9ccb10e21bb62add49a8b4c2b12a53bcca2a6e3212f5067e4223ad99c7952ae623d3285c4c7ef4fef256ae386f58ab4829d9d382fe861f156ffff7f2001000000007571007e00020000005103000000dbaa0017272c5f82cf35ff4d39658e5a59b49d056aa77a775264d31daa0a67779ce84824ca498908063a616c06016e3c5137b422bdc836404dadfa60713c8e05e861f156ffff7f2001000000007571007e00020000005103000000781d3c8b8cd5e27e2a498e1235e9eee9eb8520f025deb9d6333f053d00fc662f0493ced263ad7974ddd1f961e8e05009729daf8d2f7287f8d413e55891a0f75ae861f156ffff7f2002000000007571007e000200000051030000009de21b2dccb00411932167da09b2631383d6c29198c2f0ae73817aae4e1bf73f3c2e3741ed601b65ea082e6655076338627d0c750471becd17a673333145834ce861f156ffff7f2001000000007571007e00020000005103000000a6a0e29e3450b245403159c2d9a037d78dba6e28c848837de4f763f0510da67f42957295a7af51dab6e524d298833e21fe41f7f602a15ca1b1a9d616c0f6a048e861f156ffff7f2001000000007571007e0002000000510300000001af45b3c93a3b3df214c5bb9de9f7979293f6f8dc7e604fe6009c7003566e65bf4b0ec27039b2f548469f9f6a226a77ff3aecc1fc664ef630e640c380bcdea1e961f156ffff7f2001000000007571007e0002000000510300000068a90574417b31150736223d92e3b9d2d8dfd10056d0170d47f951d26c7c1b74af282bb3c1562d95334c2f85a4764024f1081f7d827fc9dde72369bc31693963e961f156ffff7f2006000000007571007e00020000005103000000b169732505aa2cf1e6cafe2ab661d9a95ea31b390070887a83347281d447376c118610a5e768b89edbfd40ab692c85c16cbed5d63a34186872acaa2f8a67045ae961f156ffff7f2002000000007571007e0002000000510300000021ce83026708e9cbf66119d3ffb7b0775ececece20a71a5cc1c1608fc6513f02ab07533834c02f20b71b648d9ff0674fb5dfa6ccfa4ec211419250c34764b5e0e961f156ffff7f2006000000007571007e000200000051030000001e20ff1c2f159575ece4d388254c9138e1cf4a725b2ce153deca8b6308bb3a545f9df19fff0c8f5aae9d774becf0e00bd7c62fcdf3e293fc73919e451e5c5fcbe961f156ffff7f2002000000007571007e0002000000510300000077585b6fb9cefb53409596a0ae088596944d9a7aa19f2f6b1df47aa129a5413bb3aad51a1da461aaaf84b798af59cde5850ff673fd64cae7c0f9c2eea7a9dadbe961f156ffff7f2004000000007571007e00020000005103000000e6d8789a0661b8efdf139fe87c8d26a10ce08e50cda7d03683a30f9e5c29dc22eb54a3a235615db22626d08c87323e0d69a2a478d3559c1264a1c7914ff66c37ea61f156ffff7f2000000000007571007e000200000051030000006c606d3ea69b88cfedb43a517a6b88c208d048882f2b262bbe325ccf36855675b3029802e551efccedbcf6e90e5b30d8dd32d3b05e21d4d4b28fa851c9dedf30ea61f156ffff7f2003000000007571007e000200000051030000005601bc46040b9c95f0416398f05997ca8b15760dcff3d9a9e5ca115c26395b0e544abfe2d374a9679ba1e100ac15b4e42e161971214dd0ec3beb840f8e3105e8ea61f156ffff7f2001000000007571007e00020000005103000000ce247dc40251199521cc41edd55af1e68df3fcac667654abf6aa49499331f249097904724f511ae5aabed07be334606fce2cb2e9e4499c16ee3ce801aa6d4e76ea61f156ffff7f2000000000007571007e00020000005103000000ba15622a91cb19e8f9d64970440a6f2cb363955c15c5d935a3264c38cec3864808aaf345e92fea415c4642c7f63c961d21a04674f8e9ae639d37d17893c221f9d87ff156ffff7f2001000000007571007e00020000005103000000a856c2f20bd3f269412bfaecccd4a181652e772276def2c80f59c888399ce275aa3328a6575bac6f520eb83e07c0a74a343563de6e5bd77f25cf5cfa1978e3f2d87ff156ffff7f2001000000007571007e000200000051030000007e72a77da8490584087c222443679b52690a6b21335dd65f685010c082797274a18e77b1b63489d7d2f00a1778548dc253befcf518dd03638c4fa35e69ba3a78d87ff156ffff7f2003000000007571007e00020000005103000000190e3e8424207c770752cd8cf14901aca2983e477953b781d2c33311a53d936cd8d99e5d8d92fc4db825aa41c8ffbf69f6ee551fce9a2024e236c7539f5bfc31d87ff156ffff7f2001000000007571007e0002000000510300000038e2ec0088fb0a852260dfae55f621901f0d2ff671fbf3c5c613b0cbf65a06643ded8c53ab47c046aba8b209a9501c7aee8e618a98c5aec84f645857066985d8d87ff156ffff7f2008000000007571007e00020000005103000000612893061da58c773e0e44a1246fb5dc05e70ce439b405d2e97ae231d1ffee70c08ebb3661408aad199851d9418685102dc5a4d57a6a3e9b1c5e4950dc00df85d87ff156ffff7f2000000000007571007e0002000000510300000073a0ff20b4ac2788f7b442a4e8331e1327f5a15c2e0c62d0a3282a186b6f997a875bfdf013be196f7d4d9c818891cd77a119d9ffc8b18d2d6c4054db2dfc651411aff156ffff7f2003000000007571007e00020000005103000000c283913660af415b5542d90695a06775017466fdfa588623280777b0a5b7382bbc4563a6745a9a07474c4c353df7bbd587857a777bf56051b2363ab015d15ef132c6f156ffff7f2001000000007571007e00020000005003000000e376553f93125db4542793a2e471616f9decfec8431c26d74c8a2dcfe1104a3de4063a2efb1c4772df46ec7a5f234d89872e88c8a27a2ae2a061b245fe502fe58b77d857ffff7f2004000000";
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
