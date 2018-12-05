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

package co.rsk.config;

import co.rsk.bitcoinj.core.BtcECKey;
import co.rsk.bitcoinj.core.Coin;
import co.rsk.bitcoinj.core.NetworkParameters;
import co.rsk.peg.AddressBasedAuthorizer;
import co.rsk.peg.Federation;
import org.ethereum.crypto.ECKey;
import org.spongycastle.util.encoders.Hex;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;

public class BridgeTestNetConstants extends BridgeConstants {
    private static BridgeTestNetConstants instance = new BridgeTestNetConstants();

    BridgeTestNetConstants() {
        btcParamsString = NetworkParameters.ID_TESTNET;

        List<BtcECKey> genesisFederationPublicKeys = Arrays.asList(
            BtcECKey.fromPublicOnly(Hex.decode("03cafd7627f96c04e7b2b3b38a41bb2e6e798901c62a3559f6aab8831ddfb637b8")),
            BtcECKey.fromPublicOnly(Hex.decode("038a9131b5d443c3b11ae843b96fe37766f1f48c4336c0cf050d14c081394ddc74")),
            BtcECKey.fromPublicOnly(Hex.decode("02b3e9b3814b50927df4a7522723c068b14736a69299fc87acb66f2aaaa18d2eca")),
            BtcECKey.fromPublicOnly(Hex.decode("02283d343271c711dfe4c1b906f5ed6700a14ad199fd97bb90bd65eefdd2b31517")),
            BtcECKey.fromPublicOnly(Hex.decode("02e63e367338e4c8bd0392f95f2d42e2ad8f0518aa0f84250ae61aff80de5b1cee")),
            BtcECKey.fromPublicOnly(Hex.decode("038e4051fa0c0681c0bcfc1a50f677892a82483fdd2354db57c888dda98374e73f")),
            BtcECKey.fromPublicOnly(Hex.decode("0229b62475f32190eb04db43c89923b75e4072b75bd705701196ff5918103ffa82"))
        );

        Instant genesisFederationAddressCreatedAt = ZonedDateTime.parse("2018-11-28T00:00:00Z").toInstant();

        genesisFederation = new Federation(
                genesisFederationPublicKeys,
                genesisFederationAddressCreatedAt,
                1L,
                getBtcParams()
        );

        btc2RskMinimumAcceptableConfirmations = 10;
        btc2RskMinimumAcceptableConfirmationsOnRsk = 10;
        rsk2BtcMinimumAcceptableConfirmations = 10;

        updateBridgeExecutionPeriod = 3 * 60 * 1000; // 3 minutes

        maxBtcHeadersPerRskBlock = 500;

        minimumLockTxValue = Coin.valueOf(1000000);
        minimumReleaseTxValue = Coin.valueOf(500000);

        // Passphrases are kept private
        List<ECKey> federationChangeAuthorizedKeys = Arrays.asList(
            ECKey.fromPublicOnly(Hex.decode("04a81f574f70c0be16bd9f42bf7598262ec8c70221788bffc38c235b47d58db04d3295a3f23eff201f4e298a8226a45efb37109040e0ac866354b2e1a5ddd470fb")),
            ECKey.fromPublicOnly(Hex.decode("04882c54e90d5bacd64a7cfc7cc826a82f318d1e03c5707f1d6c94e524f1f9f5f4e57517aa1634a3cbd6a5eb5c05f6888f2b9b9a89b814d3fc4d1097bba1aeb191")),
            ECKey.fromPublicOnly(Hex.decode("049131b98cf8cc9512748fa136604f0f92ed537244da07cb8c862f2ef0037b6acc9c890fffd9af4b51135012a9dbf9af30b1e7dffef3e7dad5e4780aa97471113e"))
        );

        federationChangeAuthorizer = new AddressBasedAuthorizer(
                federationChangeAuthorizedKeys,
                AddressBasedAuthorizer.MinimumRequiredCalculation.MAJORITY
        );

        // Passphrases are kept private
        List<ECKey> lockWhitelistAuthorizedKeys = Arrays.asList(
            ECKey.fromPublicOnly(Hex.decode("04eb34a3dfd033894d812c407c6d2d598d2fc275b15b33faa80e6b333a019a16db8168d83ac206c651de472c94253c2907c8a7788e80a24c5bb99c337f888fbd17"))
        );

        lockWhitelistChangeAuthorizer = new AddressBasedAuthorizer(
                lockWhitelistAuthorizedKeys,
                AddressBasedAuthorizer.MinimumRequiredCalculation.ONE
        );

        federationActivationAge = 60L;

        fundsMigrationAgeSinceActivationBegin = 60L;
        fundsMigrationAgeSinceActivationEnd = 900L;

        List<ECKey> feePerKbAuthorizedKeys = Arrays.asList(
            ECKey.fromPublicOnly(Hex.decode("04e7dc244baf11b40f033bfd915ddd097f306c49ca88c44b01f8ade349845a19bc7cd9b4dd9ea0065b773073a5dcd71740064b923f458d6c480a3ec992ad5d577b")),
            ECKey.fromPublicOnly(Hex.decode("04ccc6d9aeecf4031dab559fb73756b2c51e7928ce0ecfc46e3eda269c1c34c3b47412dd6b2ab10030b236eeae83f467d59e0088e612ea4d8d01841357af8993a4")),
            ECKey.fromPublicOnly(Hex.decode("04b0abe7ff8453240e3bf8adef4f64d520cb269e33198fea48016dcd288cfc7fabd6672e935a103390c86ab97b25ce4d54f45a4763427cc3ade883fc478fdda23f"))
        );

        feePerKbChangeAuthorizer = new AddressBasedAuthorizer(
                feePerKbAuthorizedKeys,
                AddressBasedAuthorizer.MinimumRequiredCalculation.MAJORITY
        );

        genesisFeePerKb = Coin.MICROCOIN.multiply(100);
    }

    public static BridgeTestNetConstants getInstance() {
        return instance;
    }

}
