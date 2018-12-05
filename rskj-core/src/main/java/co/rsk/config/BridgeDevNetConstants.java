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
import com.google.common.collect.Lists;
import org.ethereum.crypto.ECKey;
import org.spongycastle.util.encoders.Hex;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;

public class BridgeDevNetConstants extends BridgeConstants {
    private static BridgeDevNetConstants instance = new BridgeDevNetConstants();

    BridgeDevNetConstants() {
        btcParamsString = NetworkParameters.ID_TESTNET;

        List<BtcECKey> genesisFederationPublicKeys = Lists.newArrayList(
            BtcECKey.fromPublicOnly(Hex.decode("0234ab441aa5edb1c7341315e21408c3947cce345156c465b3336e8c6a5552f35f")),
            BtcECKey.fromPublicOnly(Hex.decode("03301f6c4422aa96d85f52a93612a0c6eeea3d04cfa32f97a7a764c67e062e992a")),
            BtcECKey.fromPublicOnly(Hex.decode("02d33a1f8f5cfa2f7be71b0002710f4c8f3ea44fef40056be7b89ed3ca0eb3431c"))
        );
        
        Instant genesisFederationAddressCreatedAt = ZonedDateTime.parse("2017-11-14T00:00:00Z").toInstant();

        // Expected federation address is:
        // 2NCEo1RdmGDj6MqiipD6DUSerSxKv79FNWX
        genesisFederation = new Federation(
                genesisFederationPublicKeys,
                genesisFederationAddressCreatedAt,
                1L,
                getBtcParams()
        );

        btc2RskMinimumAcceptableConfirmations = 1;
        btc2RskMinimumAcceptableConfirmationsOnRsk = 10;
        rsk2BtcMinimumAcceptableConfirmations = 10;

        updateBridgeExecutionPeriod = 30000; // 30secs

        maxBtcHeadersPerRskBlock = 500;

        minimumLockTxValue = Coin.valueOf(1000000);
        minimumReleaseTxValue = Coin.valueOf(500000);

        // Keys generated with GenNodeKey using generators 'auth-a' through 'auth-e'
        List<ECKey> federationChangeAuthorizedKeys = Arrays.asList(
            ECKey.fromPublicOnly(Hex.decode("04dde17c5fab31ffc53c91c2390136c325bb8690dc135b0840075dd7b86910d8ab9e88baad0c32f3eea8833446a6bc5ff1cd2efa99ecb17801bcb65fc16fc7d991")),
            ECKey.fromPublicOnly(Hex.decode("04af886c67231476807e2a8eee9193878b9d94e30aa2ee469a9611d20e1e1c1b438e5044148f65e6e61bf03e9d72e597cb9cdea96d6fc044001b22099f9ec403e2")),
            ECKey.fromPublicOnly(Hex.decode("045d4dedf9c69ab3ea139d0f0da0ad00160b7663d01ce7a6155cd44a3567d360112b0480ab6f31cac7345b5f64862205ea7ccf555fcf218f87fa0d801008fecb61")),
            ECKey.fromPublicOnly(Hex.decode("04709f002ac4642b6a87ea0a9dc76eeaa93f71b3185985817ec1827eae34b46b5d869320efb5c5cbe2a5c13f96463fe0210710b53352a4314188daffe07bd54154")),
            ECKey.fromPublicOnly(Hex.decode("0447b4aba974c61c6c4045893267346730ec965b308e7ca04a899cf06a901face3106e1eef1bdad04928cd8263522eda4872d20d3fe1ef5e551785c4a482656a6e"))
        );

        federationChangeAuthorizer = new AddressBasedAuthorizer(
                federationChangeAuthorizedKeys,
                AddressBasedAuthorizer.MinimumRequiredCalculation.MAJORITY
        );

        // Key generated with GenNodeKey using generator 'auth-lock-whitelist'
        List<ECKey> lockWhitelistAuthorizedKeys = Arrays.asList(
            ECKey.fromPublicOnly(Hex.decode("0447b4aba974c61c6c4045893267346730ec965b308e7ca04a899cf06a901face3106e1eef1bdad04928cd8263522eda4872d20d3fe1ef5e551785c4a482656a6e"))
        );

        lockWhitelistChangeAuthorizer = new AddressBasedAuthorizer(
                lockWhitelistAuthorizedKeys,
                AddressBasedAuthorizer.MinimumRequiredCalculation.ONE
        );

        federationActivationAge = 10L;

        fundsMigrationAgeSinceActivationBegin = 15L;
        fundsMigrationAgeSinceActivationEnd = 100L;

        // Key generated with GenNodeKey using generator 'auth-fee-per-kb'
        List<ECKey> feePerKbAuthorizedKeys = Arrays.asList(
            ECKey.fromPublicOnly(Hex.decode("0430c7d0146029db553d60cf11e8d39df1c63979ee2e4cd1e4d4289a5d88cfcbf3a09b06b5cbc88b5bfeb4b87a94cefab81c8d44655e7e813fc3e18f51cfe7e8a0"))
        );

        feePerKbChangeAuthorizer = new AddressBasedAuthorizer(
                feePerKbAuthorizedKeys,
                AddressBasedAuthorizer.MinimumRequiredCalculation.MAJORITY
        );

        genesisFeePerKb = Coin.MICROCOIN.multiply(100);
    }

    public static BridgeDevNetConstants getInstance() {
        return instance;
    }
}
